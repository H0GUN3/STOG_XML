package com.stog.app.auth;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.stog.app.AppConfig;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AuthViewModel extends ViewModel {
    interface DebugFixture { AuthViewModel create(Context context); }
    private static volatile DebugFixture debugFixture;

    private final AuthApiClient api;
    private final AuthStateMachine machine;
    private final MutableLiveData<AuthStateMachine.State> state = new MutableLiveData<>();
    private final MutableLiveData<TripItemReadState> tripItemState =
            new MutableLiveData<>(TripItemReadState.of(TripItemReadState.Kind.IDLE));

    private AuthViewModel() {
        api = null;
        machine = new AuthStateMachine(false, new NoSessionGateway());
        machine.setStateListener(state::setValue);
    }

    private AuthViewModel(Context context) {
        api = new AuthApiClient(AppConfig.serverBaseUri(), new SecureRefreshStore(context));
        machine = new AuthStateMachine(true, api);
        machine.setStateListener(state::setValue);
    }
    AuthViewModel(AuthStateMachine machine) {
        this.api = null;
        this.machine = machine;
        machine.setStateListener(state::setValue);
    }
    AuthViewModel(AuthApiClient api, AuthStateMachine machine) {
        this.api = api;
        this.machine = machine;
        machine.setStateListener(state::setValue);
    }

    public LiveData<AuthStateMachine.State> state() { return state; }
    public LiveData<TripItemReadState> tripItemState() { return tripItemState; }
    public AuthApiClient api() { return api; }
    /** Historical test seam; token material remains private to AuthApiClient. */
    public AuthApiClient tripApiClient() { return api; }
    public void start() { machine.start(); }
    public void showLogin() { machine.showLogin(); }
    public void showSignup() { machine.showSignup(); }
    public void logout() { machine.logout(); }
    public void sessionEnded() { machine.sessionEnded(); }
    public void attach(Context context) { }
    public void detach() { machine.cancelPending(); }
    public void login() { machine.login(); }
    public void retryProviderCleanup() { machine.retryProviderCleanup(); }

    /** Non-product diagnostic seam retained for focused current-item tests. */
    public void loadTripItem(String tripId, String tripItemId) {
        TripItemReadState.Input input = TripItemReadState.Input.parse(tripId, tripItemId);
        if (!input.valid()) {
            if (api != null) api.cancelCurrentTripItem();
            tripItemState.setValue(TripItemReadState.of(TripItemReadState.Kind.INVALID_INPUT));
            return;
        }
        if (api == null) {
            tripItemState.setValue(TripItemReadState.of(TripItemReadState.Kind.RETRYABLE_ERROR));
            return;
        }
        tripItemState.setValue(TripItemReadState.of(TripItemReadState.Kind.LOADING));
        api.currentTripItem(input.tripId(), input.tripItemId(), result -> {
            TripItemReadState next = switch (result.outcome()) {
                case SUCCESS -> TripItemReadState.content(result.item());
                case TRIP_NOT_FOUND -> TripItemReadState.of(TripItemReadState.Kind.TRIP_NOT_FOUND);
                case ITEM_NOT_FOUND -> TripItemReadState.of(TripItemReadState.Kind.ITEM_NOT_FOUND);
                case RETRYABLE_ERROR -> TripItemReadState.of(TripItemReadState.Kind.RETRYABLE_ERROR);
                case AUTH_ENDED -> TripItemReadState.of(TripItemReadState.Kind.AUTH_ENDED);
            };
            tripItemState.setValue(next);
            if (result.outcome() == AuthApiClient.TripItemOutcome.AUTH_ENDED) machine.sessionEnded();
        });
    }

    public void login(String username, String password) {
        String normalized = canonicalUsername(username);
        if (!isValidUsername(normalized)) {
            machine.localError(false, "INVALID_REQUEST");
            return;
        }
        if (!isValidPassword(password)) {
            machine.localError(false, "PASSWORD_NOT_ACCEPTABLE");
            return;
        }
        machine.login(normalized, password);
    }

    public void signup(String username, String displayName, String password, String confirmation) {
        String normalized = canonicalUsername(username);
        if (!isValidUsername(normalized) || !isValidDisplayName(displayName)) {
            machine.localError(true, "INVALID_REQUEST");
            return;
        }
        if (!isValidPassword(password) || !password.equals(confirmation)) {
            machine.localError(true, "PASSWORD_NOT_ACCEPTABLE");
            return;
        }
        machine.signup(normalized, password, displayName.strip());
    }

    public static String canonicalUsername(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidUsername(String value) {
        return value.matches("^[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]$")
            && !("admin".equals(value) || "administrator".equals(value) || "root".equals(value)
                || "stog".equals(value) || "support".equals(value) || "system".equals(value));
    }

    public static boolean isValidDisplayName(String value) {
        if (value == null) return false;
        String normalized = value.strip();
        int points = normalized.codePointCount(0, normalized.length());
        if (points < 1 || points > 40) return false;
        for (int index = 0; index < normalized.length();) {
            int point = normalized.codePointAt(index);
            if (Character.isISOControl(point)) return false;
            index += Character.charCount(point);
        }
        return true;
    }

    public static boolean isValidPassword(String value) {
        if (value == null) return false;
        int points = value.codePointCount(0, value.length());
        return points >= 8 && points <= 128 && value.getBytes(StandardCharsets.UTF_8).length <= 1024;
    }

    @Override protected void onCleared() {
        machine.cancelPending();
        if (api != null) api.close();
    }

    static void installDebugFixture(DebugFixture fixture) {
        requireDebugFixtureBuild();
        debugFixture = fixture;
    }

    static void clearDebugFixture() {
        requireDebugFixtureBuild();
        debugFixture = null;
    }

    private static void requireDebugFixtureBuild() {
        if (!com.stog.app.BuildConfig.DEBUG) throw new IllegalStateException("Debug fixtures are unavailable");
    }

    public static final class Factory implements ViewModelProvider.Factory {
        private final Context applicationContext;
        public Factory(Context context) { applicationContext = context.getApplicationContext(); }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(AuthViewModel.class)) throw new IllegalArgumentException("Unknown ViewModel");
            DebugFixture fixture = debugFixture;
            if (com.stog.app.BuildConfig.DEBUG && fixture != null) return modelClass.cast(fixture.create(applicationContext));
            return modelClass.cast(AppConfig.isConfigured() ? new AuthViewModel(applicationContext) : new AuthViewModel());
        }
    }

    private static final class NoSessionGateway implements AuthStateMachine.SessionGateway {
        @Override public void restore(AuthStateMachine.CompletionCallback callback) { callback.completed(false); }
        @Override public void login(String username, String password, AuthStateMachine.AuthCallback callback) { callback.completed(false, "INVALID_REQUEST"); }
        @Override public void signup(String username, String password, String displayName, AuthStateMachine.AuthCallback callback) { callback.completed(false, "INVALID_REQUEST"); }
        @Override public void logout(AuthStateMachine.CompletionCallback callback) { callback.completed(false); }
    }
}
