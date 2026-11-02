package com.stog.app.auth;

import java.util.Objects;

/** Credential-free finite state owner for native session restoration and auth submission. */
public final class AuthStateMachine {
    public enum Kind {
        MISSING_CONFIGURATION,
        RESTORING,
        SIGNED_OUT,
        SIGNED_OUT_LOGIN,
        SIGNED_OUT_SIGNUP,
        LAUNCHING_LOGIN,
        EXCHANGING,
        SUBMITTING_LOGIN,
        SUBMITTING_SIGNUP,
        SIGNED_IN,
        SIGNED_IN_CLEANUP_UNCONFIRMED,
        ERROR,
        ERROR_CLEANUP_UNCONFIRMED,
        CLEANING_UP_PROVIDER,
        LOGGING_OUT,
        SIGNED_OUT_REVOKE_UNCONFIRMED
    }

    public record State(Kind kind, String errorCode) {
        public State(Kind kind) { this(kind, null); }
    }
    public interface CompletionCallback { void completed(boolean succeeded); }
    public interface AuthCallback { void completed(boolean succeeded, String errorCode); }
    public interface LoginCallback {
        void succeeded(String providerAccessToken);
        void cancelled();
        void failed();
    }
    /** Retained only for focused historical tests; production no longer supplies a provider gateway. */
    public interface KakaoGateway {
        void login(LoginCallback callback);
        void cleanup(CompletionCallback callback);
    }
    public interface SessionGateway {
        void restore(CompletionCallback callback);
        default void login(String username, String password, AuthCallback callback) { callback.completed(false, "INVALID_REQUEST"); }
        default void signup(String username, String password, String displayName, AuthCallback callback) { callback.completed(false, "INVALID_REQUEST"); }
        default void exchange(String providerAccessToken, CompletionCallback callback) { callback.completed(false); }
        void logout(CompletionCallback callback);
    }
    public interface StateListener { void changed(State state); }

    private final boolean configured;
    private final SessionGateway session;
    private KakaoGateway kakao;
    private StateListener listener;
    private State state;
    private long attempt;

    public AuthStateMachine(boolean configured, SessionGateway session) {
        this.configured = configured;
        this.session = Objects.requireNonNull(session);
        state = new State(configured ? Kind.SIGNED_OUT_LOGIN : Kind.MISSING_CONFIGURATION, null);
    }
    public AuthStateMachine(boolean configured, KakaoGateway kakao, SessionGateway session) {
        this(configured, session);
        this.kakao = kakao;
    }

    public synchronized State state() { return state; }

    public synchronized void setStateListener(StateListener value) {
        listener = value;
        value.changed(state);
    }

    public synchronized void cancelPending() {
        attempt++;
        if (state.kind() == Kind.SUBMITTING_LOGIN || state.kind() == Kind.SUBMITTING_SIGNUP || state.kind() == Kind.RESTORING) {
            update(Kind.SIGNED_OUT_LOGIN, null);
        }
    }
    public synchronized void cancelPendingLogin() { cancelPending(); }
    public synchronized void setKakaoGateway(KakaoGateway gateway) { kakao = gateway; }

    public void start() {
        final long current;
        synchronized (this) {
            if (!configured || state.kind() != Kind.SIGNED_OUT_LOGIN) return;
            current = ++attempt;
            update(Kind.RESTORING, null);
        }
        session.restore(succeeded -> {
            synchronized (AuthStateMachine.this) {
                if (attempt != current) return;
                update(succeeded ? Kind.SIGNED_IN : Kind.SIGNED_OUT_LOGIN, null);
            }
        });
    }

    public synchronized void showLogin() {
        if (state.kind() == Kind.SIGNED_IN || state.kind() == Kind.LOGGING_OUT || state.kind() == Kind.MISSING_CONFIGURATION) return;
        ++attempt;
        update(Kind.SIGNED_OUT_LOGIN, null);
    }

    public synchronized void showSignup() {
        if (state.kind() == Kind.SIGNED_IN || state.kind() == Kind.LOGGING_OUT || state.kind() == Kind.MISSING_CONFIGURATION) return;
        ++attempt;
        update(Kind.SIGNED_OUT_SIGNUP, null);
    }

    public void login(String username, String password) {
        final long current;
        synchronized (this) {
            if (!configured || !(state.kind() == Kind.SIGNED_OUT_LOGIN || state.kind() == Kind.ERROR
                    || state.kind() == Kind.SIGNED_OUT_REVOKE_UNCONFIRMED)) return;
            current = ++attempt;
            update(Kind.SUBMITTING_LOGIN, null);
        }
        session.login(username, password, (succeeded, errorCode) -> completeNative(current, Kind.SIGNED_OUT_LOGIN, succeeded, errorCode));
    }

    /** Historical provider seam; no production code invokes this path. */
    public void login() {
        final long current;
        final KakaoGateway gateway;
        synchronized (this) {
            if (!configured || !(state.kind() == Kind.SIGNED_OUT || state.kind() == Kind.SIGNED_OUT_LOGIN
                    || state.kind() == Kind.ERROR || state.kind() == Kind.SIGNED_OUT_REVOKE_UNCONFIRMED) || kakao == null) return;
            current = ++attempt;
            gateway = kakao;
            update(Kind.LAUNCHING_LOGIN, null);
        }
        gateway.login(new LoginCallback() {
            @Override public void succeeded(String token) {
                synchronized (AuthStateMachine.this) {
                    if (attempt != current) return;
                    update(Kind.EXCHANGING, null);
                }
                session.exchange(token, exchanged -> legacyExchangeCompleted(current, exchanged));
            }
            @Override public void cancelled() { legacyFinish(current, Kind.SIGNED_OUT); }
            @Override public void failed() { legacyFinish(current, Kind.ERROR); }
        });
    }

    public void signup(String username, String password, String displayName) {
        final long current;
        synchronized (this) {
            if (!configured || !(state.kind() == Kind.SIGNED_OUT_SIGNUP || state.kind() == Kind.ERROR)) return;
            current = ++attempt;
            update(Kind.SUBMITTING_SIGNUP, null);
        }
        session.signup(username, password, displayName,
                (succeeded, errorCode) -> completeNative(current, Kind.SIGNED_OUT_SIGNUP, succeeded, errorCode));
    }

    public synchronized void localError(boolean signup, String errorCode) {
        if (state.kind() == Kind.SIGNED_IN || state.kind() == Kind.LOGGING_OUT || state.kind() == Kind.MISSING_CONFIGURATION) return;
        ++attempt;
        update(Kind.ERROR, errorCode + (signup ? ":signup" : ":login"));
    }

    public synchronized void sessionEnded() {
        if (state.kind() == Kind.SIGNED_IN || state.kind() == Kind.SIGNED_IN_CLEANUP_UNCONFIRMED) {
            ++attempt;
            update(Kind.SIGNED_OUT_LOGIN, null);
        }
    }

    public void logout() {
        final long current;
        synchronized (this) {
            if (state.kind() != Kind.SIGNED_IN && state.kind() != Kind.SIGNED_IN_CLEANUP_UNCONFIRMED) return;
            current = ++attempt;
            update(Kind.LOGGING_OUT, null);
        }
        session.logout(revoked -> {
            synchronized (AuthStateMachine.this) {
                if (attempt == current) update(revoked ? Kind.SIGNED_OUT_LOGIN : Kind.SIGNED_OUT_REVOKE_UNCONFIRMED, null);
            }
        });
    }

    private void completeNative(long current, Kind fallback, boolean succeeded, String errorCode) {
        synchronized (this) {
            if (attempt != current) return;
            update(succeeded ? Kind.SIGNED_IN : Kind.ERROR,
                    succeeded ? null : errorCode + (fallback == Kind.SIGNED_OUT_SIGNUP ? ":signup" : ":login"));
        }
    }

    public void retryProviderCleanup() {
        final long current;
        final KakaoGateway gateway;
        synchronized (this) {
            if (state.kind() != Kind.ERROR_CLEANUP_UNCONFIRMED || kakao == null) return;
            current = ++attempt;
            gateway = kakao;
            update(Kind.CLEANING_UP_PROVIDER, null);
        }
        gateway.cleanup(cleaned -> legacyFinish(current, cleaned ? Kind.ERROR : Kind.ERROR_CLEANUP_UNCONFIRMED));
    }

    private void legacyExchangeCompleted(long current, boolean exchanged) {
        final KakaoGateway gateway;
        synchronized (this) {
            if (attempt != current) return;
            gateway = kakao;
            if (gateway == null) {
                update(exchanged ? Kind.SIGNED_IN_CLEANUP_UNCONFIRMED : Kind.ERROR_CLEANUP_UNCONFIRMED, null);
                return;
            }
        }
        if (!exchanged) {
            gateway.cleanup(cleaned -> legacyFinish(current, cleaned ? Kind.ERROR : Kind.ERROR_CLEANUP_UNCONFIRMED));
        } else {
            gateway.cleanup(cleaned -> legacyFinish(current, cleaned ? Kind.SIGNED_IN : Kind.SIGNED_IN_CLEANUP_UNCONFIRMED));
        }
    }

    private synchronized void legacyFinish(long current, Kind kind) {
        if (attempt == current) update(kind, null);
    }

    private void update(Kind kind, String errorCode) {
        state = new State(kind, errorCode);
        if (listener != null) listener.changed(state);
    }
}
