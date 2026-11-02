package com.stog.backend;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

@Component
final class PlaceCursorCodec {
    static final int MAX_TOKEN_LENGTH = 4096;
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final byte[] AAD = "STOG_PLACE_CURSOR_V1".getBytes(StandardCharsets.US_ASCII);

    private final SecretKey key;
    private final SecureRandom random;
    private final Clock clock;

    PlaceCursorCodec() { this(newKey(), new SecureRandom(), Clock.systemUTC()); }
    PlaceCursorCodec(SecretKey key, SecureRandom random, Clock clock) {
        this.key = key; this.random = random; this.clock = clock;
    }

    String encode(UUID principal, int limit, String query, String region, String category,
            String lastName, UUID lastPlaceId) {
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(payload)) {
                Instant issued = clock.instant();
                out.writeByte(VERSION);
                out.writeLong(issued.toEpochMilli());
                out.writeLong(issued.plus(LIFETIME).toEpochMilli());
                writeUuid(out, principal);
                out.writeInt(limit);
                writeString(out, query);
                writeNullable(out, region);
                writeNullable(out, category);
                writeString(out, lastName);
                writeUuid(out, lastPlaceId);
            }
            byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(payload.toByteArray());
            byte[] token = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(encrypted, 0, token, nonce.length, encrypted.length);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            if (encoded.length() > MAX_TOKEN_LENGTH) throw new IllegalStateException("Place cursor exceeds bound");
            return encoded;
        } catch (PlaceCatalogException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Place cursor encryption failed", exception);
        }
    }

    Cursor decode(String token, UUID principal, int requestedLimit, String query, String region, String category) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH || !isAscii(token)
                || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length <= NONCE_BYTES + 16) throw invalid();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS,
                java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES)));
            cipher.updateAAD(AAD);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                    cipher.doFinal(java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length))))) {
                if (in.readUnsignedByte() != VERSION) throw invalid();
                Instant issued = Instant.ofEpochMilli(in.readLong());
                Instant expires = Instant.ofEpochMilli(in.readLong());
                Instant now = clock.instant();
                if (!expires.equals(issued.plus(LIFETIME)) || now.isBefore(issued) || !now.isBefore(expires)) throw invalid();
                if (!readUuid(in).equals(principal)) throw invalid();
                int limit = in.readInt();
                if (limit < 1 || limit > 50 || requestedLimit != -1 && requestedLimit != limit) throw invalid();
                String boundQuery = readString(in, 400);
                String boundRegion = readNullable(in, 400);
                String boundCategory = readNullable(in, 400);
                if (!boundQuery.equals(query) || !java.util.Objects.equals(boundRegion, region)
                        || !java.util.Objects.equals(boundCategory, category)) throw invalid();
                String lastName = readString(in, 800);
                UUID lastPlace = readUuid(in);
                requireEnd(in);
                return new Cursor(limit, lastName, lastPlace);
            }
        } catch (PlaceCatalogException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void writeNullable(DataOutputStream out, String value) throws Exception {
        if (value == null) out.writeInt(-1); else writeString(out, value);
    }
    private static String readString(DataInputStream in, int maxBytes) throws Exception {
        int length = in.readInt();
        if (length < 0 || length > maxBytes) throw invalid();
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw invalid();
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw invalid();
        return value;
    }
    private static String readNullable(DataInputStream in, int maxBytes) throws Exception {
        int length = in.readInt();
        if (length == -1) return null;
        if (length < 0 || length > maxBytes) throw invalid();
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw invalid();
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw invalid();
        return value;
    }
    private static void writeUuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID readUuid(DataInputStream in) throws Exception { return new UUID(in.readLong(), in.readLong()); }
    private static void requireEnd(DataInputStream in) throws Exception {
        try { in.readByte(); throw invalid(); } catch (EOFException expected) { }
    }
    private static boolean isAscii(String value) {
        for (int i=0;i<value.length();i++) if (value.charAt(i)>0x7f) return false; return true;
    }
    private static SecretKey newKey() {
        try { KeyGenerator generator=KeyGenerator.getInstance("AES"); generator.init(256,new SecureRandom()); return generator.generateKey(); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("AES-256 unavailable",exception); }
    }
    private static PlaceCatalogException invalid() { return new PlaceCatalogException(400,"INVALID_CURSOR","The cursor is invalid"); }
    record Cursor(int limit, String lastName, UUID lastPlaceId) {}
}
