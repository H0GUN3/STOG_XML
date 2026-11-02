package com.stog.app.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureRefreshStore {
    static final String PREFERENCES_NAME = "stog_secure_auth";
    static final String ENCRYPTED_REFRESH_KEY = "encrypted_refresh";
    private static final String KEY_ALIAS = "stog_refresh_aes_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences preferences;

    public SecureRefreshStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void write(String refreshToken) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer value = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        value.put((byte) iv.length).put(iv).put(ciphertext);
        if (!preferences.edit().putString(ENCRYPTED_REFRESH_KEY,
                Base64.encodeToString(value.array(), Base64.NO_WRAP)).commit()) {
            throw new GeneralSecurityException("Refresh credential could not be stored");
        }
    }

    public synchronized String read() {
        String encoded = preferences.getString(ENCRYPTED_REFRESH_KEY, null);
        if (encoded == null) return null;
        try {
            ByteBuffer value = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int ivLength = Byte.toUnsignedInt(value.get());
            if (ivLength < 12 || ivLength > 16 || value.remaining() <= ivLength) throw new GeneralSecurityException();
            byte[] iv = new byte[ivLength];
            value.get(iv);
            byte[] ciphertext = new byte[value.remaining()];
            value.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException exception) {
            clear();
            deleteKey();
            return null;
        }
    }

    public synchronized void clear() {
        preferences.edit().remove(ENCRYPTED_REFRESH_KEY).apply();
    }

    private static void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException | java.io.IOException ignored) {
            // The ciphertext is already gone; the next write remains fail-closed if the keystore is unavailable.
        }
    }

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException exception) {
            throw new GeneralSecurityException(exception);
        }
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey secretKey) return secretKey;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
