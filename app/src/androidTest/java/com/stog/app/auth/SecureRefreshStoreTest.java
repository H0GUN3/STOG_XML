package com.stog.app.auth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public final class SecureRefreshStoreTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final SecureRefreshStore store = new SecureRefreshStore(context);

    @After public void clear() { store.clear(); }

    @Test public void refreshTokenIsEncryptedAndRoundTrips() throws Exception {
        String token = "fixture-refresh-token-never-log";
        store.write(token);

        SharedPreferences preferences = context.getSharedPreferences(
            SecureRefreshStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        String ciphertext = preferences.getString(SecureRefreshStore.ENCRYPTED_REFRESH_KEY, "");
        assertFalse(ciphertext.isEmpty());
        assertFalse(ciphertext.contains(token));
        assertEquals(token, store.read());
    }

    @Test public void malformedCiphertextIsClearedAndKeystoreCanRecover() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(
            SecureRefreshStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().putString(SecureRefreshStore.ENCRYPTED_REFRESH_KEY, "not-valid-ciphertext").commit();

        assertNull(store.read());
        assertFalse(preferences.contains(SecureRefreshStore.ENCRYPTED_REFRESH_KEY));
        store.write("fixture-recovered-refresh-token");
        assertEquals("fixture-recovered-refresh-token", store.read());
    }
}
