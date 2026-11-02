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
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

@Component
final class TripCursorCodec {
    enum Endpoint { TRIP_LIST, BASKET_LIST, MEMBER_LIST }

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final byte[] AAD = "STOG_TRIP_CURSOR_V1".getBytes(StandardCharsets.US_ASCII);

    private final SecretKey key;
    private final SecureRandom random;
    private final Clock clock;

    TripCursorCodec() {
        this(newKey(), new SecureRandom(), Clock.systemUTC());
    }

    TripCursorCodec(SecretKey key, SecureRandom random, Clock clock) {
        this.key = key;
        this.random = random;
        this.clock = clock;
    }

    String encodeTrip(UUID principal, int limit, LocalDate asOf, int bucket, LocalDate sortDate, UUID tripId) {
        return encode(out -> {
            writeCommon(out, principal, Endpoint.TRIP_LIST, limit);
            out.writeLong(asOf.toEpochDay());
            out.writeByte(bucket);
            out.writeLong(sortDate.toEpochDay());
            writeUuid(out, tripId);
        });
    }

    String encodeBasket(UUID principal, int limit, UUID tripId, long planVersion, Instant createdAt, UUID itemId) {
        return encode(out -> {
            writeCommon(out, principal, Endpoint.BASKET_LIST, limit);
            writeUuid(out, tripId);
            out.writeByte(1); // exact BASKET state
            out.writeLong(planVersion);
            out.writeLong(createdAt.getEpochSecond());
            out.writeInt(createdAt.getNano());
            writeUuid(out, itemId);
        });
    }

    String encodeMember(UUID principal, int limit, UUID tripId, long planVersion, int roleBucket,
            UUID memberUserId) {
        return encode(out -> {
            writeCommon(out, principal, Endpoint.MEMBER_LIST, limit);
            writeUuid(out, tripId);
            out.writeLong(planVersion);
            out.writeByte(roleBucket);
            writeUuid(out, memberUserId);
        });
    }

    TripCursor decodeTrip(String token, UUID principal, int requestedLimit) {
        try (DataInputStream in = decrypt(token)) {
            Common common = readCommon(in, principal, Endpoint.TRIP_LIST, requestedLimit);
            LocalDate asOf = LocalDate.ofEpochDay(in.readLong());
            int bucket = in.readUnsignedByte();
            if (bucket > 2 || asOf.getYear() < 1 || asOf.getYear() > 9999) throw invalid();
            LocalDate sortDate = LocalDate.ofEpochDay(in.readLong());
            if (sortDate.getYear() < 1 || sortDate.getYear() > 9999) throw invalid();
            UUID tripId = readUuid(in);
            requireEnd(in);
            return new TripCursor(common.limit(), asOf, bucket, sortDate, tripId);
        } catch (TripProductException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    BasketCursor decodeBasket(String token, UUID principal, int requestedLimit, UUID expectedTrip) {
        try (DataInputStream in = decrypt(token)) {
            Common common = readCommon(in, principal, Endpoint.BASKET_LIST, requestedLimit);
            UUID tripId = readUuid(in);
            if (!tripId.equals(expectedTrip) || in.readUnsignedByte() != 1) throw invalid();
            long version = in.readLong();
            if (version < 0) throw invalid();
            Instant createdAt = Instant.ofEpochSecond(in.readLong(), in.readInt());
            UUID itemId = readUuid(in);
            requireEnd(in);
            return new BasketCursor(common.limit(), tripId, version, createdAt, itemId);
        } catch (TripProductException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    MemberCursor decodeMember(String token, UUID principal, int requestedLimit, UUID expectedTrip) {
        try (DataInputStream in = decrypt(token)) {
            Common common = readCommon(in, principal, Endpoint.MEMBER_LIST, requestedLimit);
            UUID tripId = readUuid(in);
            if (!tripId.equals(expectedTrip)) throw invalid();
            long version = in.readLong();
            int roleBucket = in.readUnsignedByte();
            if (version < 0 || roleBucket > 1) throw invalid();
            UUID memberUserId = readUuid(in);
            requireEnd(in);
            return new MemberCursor(common.limit(), tripId, version, roleBucket, memberUserId);
        } catch (TripProductException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String encode(Writer writer) {
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
                writer.write(out);
            }
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(payloadBytes.toByteArray());
            byte[] token = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(encrypted, 0, token, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (Exception exception) {
            throw new IllegalStateException("Cursor encryption failed", exception);
        }
    }

    private void writeCommon(DataOutputStream out, UUID principal, Endpoint endpoint, int limit) throws Exception {
        Instant issued = clock.instant();
        out.writeByte(VERSION);
        out.writeLong(issued.toEpochMilli());
        out.writeLong(issued.plus(LIFETIME).toEpochMilli());
        writeUuid(out, principal);
        out.writeByte(endpoint.ordinal());
        out.writeInt(limit);
    }

    private DataInputStream decrypt(String token) {
        if (token == null || token.isEmpty() || token.length() > 1024 || !isAscii(token)
                || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length <= NONCE_BYTES + 16) throw invalid();
            byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new DataInputStream(new ByteArrayInputStream(cipher.doFinal(encrypted)));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw invalid();
        }
    }

    private Common readCommon(DataInputStream in, UUID principal, Endpoint endpoint, int requestedLimit) throws Exception {
        if (in.readUnsignedByte() != VERSION) throw invalid();
        Instant issued = Instant.ofEpochMilli(in.readLong());
        Instant expires = Instant.ofEpochMilli(in.readLong());
        Instant now = clock.instant();
        if (!expires.equals(issued.plus(LIFETIME)) || now.isBefore(issued) || !now.isBefore(expires)) throw invalid();
        if (!readUuid(in).equals(principal)) throw invalid();
        int endpointValue = in.readUnsignedByte();
        if (endpointValue != endpoint.ordinal()) throw invalid();
        int limit = in.readInt();
        if (limit < 1 || limit > 50 || (requestedLimit != -1 && requestedLimit != limit)) throw invalid();
        return new Common(limit);
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) return false;
        return true;
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws Exception {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void requireEnd(DataInputStream in) throws Exception {
        try {
            in.readByte();
            throw invalid();
        } catch (EOFException expected) {
            // exact payload consumed
        }
    }

    private static SecretKey newKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, new SecureRandom());
            return generator.generateKey();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256 is unavailable", exception);
        }
    }

    private static TripProductException invalid() {
        return new TripProductException(400, "INVALID_CURSOR", "The cursor is invalid");
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws Exception; }
    private record Common(int limit) {}
    record TripCursor(int limit, LocalDate asOfDate, int bucket, LocalDate sortDate, UUID tripId) {}
    record BasketCursor(int limit, UUID tripId, long planVersion, Instant createdAt, UUID itemId) {}
    record MemberCursor(int limit, UUID tripId, long planVersion, int roleBucket, UUID memberUserId) {}
}
