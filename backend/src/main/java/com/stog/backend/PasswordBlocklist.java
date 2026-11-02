package com.stog.backend;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

@Component
final class PasswordBlocklist {
    private static final String RESOURCE = "/security/common-passwords.txt.gz.b64";
    private static final int EXPECTED_VALUES = 10_001;
    private static final String EXPECTED_SHA256 =
        "68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce";

    private final Set<String> literals;

    PasswordBlocklist() {
        Set<String> loadedLiterals = new HashSet<>();
        try (InputStream input = PasswordBlocklist.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalidResource();
            String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                .replaceAll("\\s+", "");
            byte[] compressed = Base64.getDecoder().decode(encoded);
            byte[] corpus;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                gzip.transferTo(output);
                corpus = output.toByteArray();
            }
            if (!EXPECTED_SHA256.equals(hex(sha256(corpus)))) throw invalidResource();
            String text = new String(corpus, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (line.isEmpty()) continue;
                add(loadedLiterals, canonical(line));
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidResource();
        }
        if (loadedLiterals.size() != EXPECTED_VALUES) throw invalidResource();
        literals = Set.copyOf(loadedLiterals);
    }

    boolean contains(String normalizedPassword, String username) {
        String candidate = canonical(normalizedPassword);
        if (candidate.equals(username) || candidate.equals("stog" + username)
                || candidate.equals(username + "stog")) {
            return true;
        }
        return literals.contains(candidate);
    }

    int size() { return literals.size(); }

    private static void add(Set<String> values, String value) {
        if (value.isEmpty() || !values.add(value)) throw invalidResource();
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private static IllegalStateException invalidResource() {
        return new IllegalStateException("Native password blocklist resource is invalid");
    }
}
