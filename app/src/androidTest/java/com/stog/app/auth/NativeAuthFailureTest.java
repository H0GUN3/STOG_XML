package com.stog.app.auth;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class NativeAuthFailureTest {
    @Test public void communicationFailureExitsSignupAndLoginLoading() throws Exception {
        Context application = ApplicationProvider.getApplicationContext();
        Context isolated = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("native_auth_failure_test_" + name, mode);
            }
        };
        SecureRefreshStore store = new SecureRefreshStore(isolated);
        store.clear();
        ExecutorService serverWorker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            for (boolean signup : new boolean[] {true, false}) {
                Future<?> disconnected = serverWorker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoLinger(true, 0);
                    }
                    return null;
                });
                try (AuthApiClient client = new AuthApiClient(
                        URI.create("http://127.0.0.1:" + server.getLocalPort()), store)) {
                    AuthStateMachine machine = new AuthStateMachine(true, client);
                    if (signup) machine.showSignup();
                    CountDownLatch failed = new CountDownLatch(1);
                    machine.setStateListener(state -> {
                        if (state.kind() == AuthStateMachine.Kind.ERROR) failed.countDown();
                    });
                    if (signup) machine.signup("fixture-user", "fixture-password", "Fixture");
                    else machine.login("fixture-user", "fixture-password");
                    assertTrue(failed.await(10, TimeUnit.SECONDS));
                    disconnected.get(10, TimeUnit.SECONDS);
                    assertEquals(AuthStateMachine.Kind.ERROR, machine.state().kind());
                    assertEquals("NETWORK_ERROR:" + (signup ? "signup" : "login"),
                            machine.state().errorCode());
                    assertNull(client.accessToken());
                    assertNull(store.read());
                }
            }
        } finally {
            serverWorker.shutdownNow();
            assertTrue(serverWorker.awaitTermination(10, TimeUnit.SECONDS));
            store.clear();
        }
    }
}
