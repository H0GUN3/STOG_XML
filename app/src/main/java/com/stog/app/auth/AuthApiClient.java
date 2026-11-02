package com.stog.app.auth;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AuthApiClient implements AuthStateMachine.SessionGateway, AutoCloseable {
    private static final int TIMEOUT_MILLIS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 32_768;
    private static final int MAX_PLACE_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_DISPLAY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_THUMBNAIL_BYTES = 512 * 1024;

    private final URI baseUri;
    private final SecureRefreshStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AuthStateMachine.CompletionCallback> restoreCallbacks = new ArrayList<>();
    private volatile HttpURLConnection activeConnection;
    private volatile String accessToken;
    private boolean restoreInFlight;
    private long authGeneration;
    private long readGeneration;
    private long collaborationGeneration;
    private long coverGeneration;
    private long placeGeneration;

    public interface NativeAuthCallback {
        void completed(boolean succeeded, String errorCode);
    }

    public AuthApiClient(URI baseUri, SecureRefreshStore store) {
        this.baseUri = baseUri;
        this.store = store;
    }

    @Override public void restore(AuthStateMachine.CompletionCallback callback) {
        synchronized (restoreCallbacks) {
            restoreCallbacks.add(callback);
            if (restoreInFlight) return;
            restoreInFlight = true;
        }
        final long generation;
        synchronized (this) { generation = authGeneration; }
        executor.execute(() -> {
            boolean succeeded = false;
            String refresh = store.read();
            if (refresh != null) {
                try {
                    Tokens tokens = tokenRequest("/api/auth/refresh", new JSONObject().put("refresh_token", refresh));
                    succeeded = accept(tokens, generation);
                } catch (IOException | JSONException | GeneralSecurityException ignored) {
                    clearLocal(generation);
                }
            }
            List<AuthStateMachine.CompletionCallback> callbacks;
            synchronized (restoreCallbacks) {
                callbacks = new ArrayList<>(restoreCallbacks);
                restoreCallbacks.clear();
                restoreInFlight = false;
            }
            boolean result = succeeded;
            main.post(() -> {
                for (AuthStateMachine.CompletionCallback item : callbacks) item.completed(result);
            });
        });
    }

    @Override public void login(String username, String password, AuthStateMachine.AuthCallback callback) {
        try {
            nativeAuth("/api/auth/login", new JSONObject()
                .put("username", username)
                .put("password", password), HttpURLConnection.HTTP_OK, callback);
        } catch (JSONException exception) {
            callback.completed(false, "INVALID_REQUEST");
        }
    }

    @Override public void signup(String username, String password, String displayName, AuthStateMachine.AuthCallback callback) {
        try {
            nativeAuth("/api/auth/signup", new JSONObject()
                .put("username", username)
                .put("password", password)
                .put("display_name", displayName), HttpURLConnection.HTTP_CREATED, callback);
        } catch (JSONException exception) {
            callback.completed(false, "INVALID_REQUEST");
        }
    }

    /** Backend compatibility seam retained for focused session/domain tests; the UI never calls it. */
    @Override public void exchange(String providerAccessToken, AuthStateMachine.CompletionCallback callback) {
        final long generation;
        synchronized (this) { generation = authGeneration; }
        executor.execute(() -> {
            boolean succeeded = false;
            try {
                Tokens tokens = tokenRequest("/api/auth/kakao/exchange",
                        new JSONObject().put("kakao_access_token", providerAccessToken));
                succeeded = accept(tokens, generation);
            } catch (IOException | JSONException | GeneralSecurityException ignored) {
                clearLocal(generation);
            }
            complete(callback, succeeded);
        });
    }

    private void nativeAuth(String path, JSONObject body, int successStatus, AuthStateMachine.AuthCallback callback) {
        final long generation;
        synchronized (this) { generation = authGeneration; }
        executor.execute(() -> {
            boolean succeeded = false;
            String errorCode = "NETWORK_ERROR";
            try {
                Response response = request(path, body);
                if (response.statusCode() != successStatus) {
                    errorCode = errorCodeValue(response);
                } else {
                    Tokens tokens = parseTokens(response);
                    succeeded = accept(tokens, generation);
                    if (!succeeded) errorCode = "NETWORK_ERROR";
                }
            } catch (IOException | JSONException | GeneralSecurityException ignored) {
                synchronized (this) {
                    // Clear this attempt's credentials without invalidating its failure callback.
                    if (authGeneration == generation) clearLocalUnconditionally();
                }
            }
            completeNativeAuth(generation, callback, succeeded, errorCode);
        });
    }

    @Override public void logout(AuthStateMachine.CompletionCallback callback) {
        synchronized (this) {
            authGeneration++;
            readGeneration++;
            collaborationGeneration++;
            placeGeneration++;
            accessToken = null;
        }
        executor.execute(() -> {
            boolean revoked = false;
            String refresh = store.read();
            try {
                if (refresh != null) {
                    revoked = request("/api/auth/logout", new JSONObject().put("refresh_token", refresh))
                        .statusCode() == HttpURLConnection.HTTP_NO_CONTENT;
                }
            } catch (IOException | JSONException ignored) {
                // Local credentials are still removed below; the UI exposes that server revoke is unconfirmed.
            } finally {
                clearLocalUnconditionally();
            }
            complete(callback, revoked);
        });
    }

    public void currentTripItem(String tripId, String tripItemId, TripItemCallback callback) {
        final long request;
        final long generation;
        synchronized (this) {
            request = ++readGeneration;
            generation = authGeneration;
        }
        executor.execute(() -> readTripItem(request, generation, tripId, tripItemId, callback));
    }

    public synchronized void cancelCurrentTripItem() { readGeneration++; }

    /** R1 trip product calls share the authenticated transport and its single refresh retry. */
    public void currentTrips(TripPageCallback callback) { currentTrips(null, callback); }

    public void currentTrips(String cursor, TripPageCallback callback) {
        if (cursor != null && (!cursor.matches("[A-Za-z0-9_-]{1,1024}"))) {
            main.post(() -> callback.completed(TripPageResult.invalidCursor()));
            return;
        }
        runTripPageRequest("GET", "/api/trips?limit=20" + (cursor == null ? "" : "&cursor=" + cursor), null, callback);
    }

    public void currentDisplayName(DisplayNameCallback callback) {
        final long generation;
        synchronized (this) { generation = authGeneration; }
        executor.execute(() -> {
            String name = null;
            try {
                ProtectedResponse response = protectedResponse("GET", "/api/me", null, generation);
                if (!response.authEnded() && response.response().statusCode() == HttpURLConnection.HTTP_OK) {
                    JSONObject root = new JSONObject(response.response().body());
                    name = root.isNull("display_name") ? null : root.getString("display_name");
                }
            } catch (IOException | JSONException exception) { name = null; }
            final String result = name;
            main.post(() -> { synchronized (AuthApiClient.this) { if (authGeneration != generation) return; } callback.completed(result); });
        });
    }

    public void createTrip(TripCreateRequest request, TripCallback callback) {
        try {
            JSONObject body = new JSONObject().put("name", request.name()).put("region_label", request.regionLabel())
                .put("start_date", request.startDate()).put("end_date", request.endDate())
                .put("request_id", request.requestId());
            runTripRequest("POST", "/api/trips", body, true, callback);
        } catch (JSONException exception) { main.post(() -> callback.completed(TripResult.retryable())); }
    }

    public void tripDetail(String tripId, TripCallback callback) {
        runTripRequest("GET", "/api/trips/" + tripId, null, false, callback);
    }

    public void tripBasket(String tripId, BasketCallback callback) {
        final long request;
        final long generation;
        synchronized (this) { request = ++readGeneration; generation = authGeneration; }
        executor.execute(() -> {
            BasketResult result;
            try {
                ProtectedResponse response = protectedResponse("GET", "/api/trips/" + tripId
                    + "/trip-items?state=BASKET&limit=20", null, generation);
                result = response.authEnded() ? BasketResult.authEnded() : parseBasket(response.response());
            } catch (IOException | JSONException exception) { result = BasketResult.retryable(); }
            completeBasket(request, callback, result);
        });
    }

    /** R2a collaboration routes use the same Bearer/one-refresh transport as R1. */
    public void updateTrip(String tripId, TripUpdateRequest request, CollaborationCallback callback) {
        try {
            JSONObject body = new JSONObject().put("name", request.name()).put("region_label", request.regionLabel())
                .put("start_date", request.startDate()).put("end_date", request.endDate())
                .put("expected_plan_version", request.expectedPlanVersion()).put("request_id", request.requestId());
            runCollaborationRequest("PUT", "/api/trips/" + tripId, body, CollaborationPayload.TRIP, callback);
        } catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }

    public void members(String tripId, String cursor, CollaborationCallback callback) {
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) { postCollaboration(callback, CollaborationResult.invalidCursor()); return; }
        runCollaborationRequest("GET", "/api/trips/" + tripId + "/members?limit=20"
            + (cursor == null ? "" : "&cursor=" + cursor), null, CollaborationPayload.MEMBERS, callback);
    }

    public void updateMemberRole(String tripId, String memberUserId, String role, long expectedVersion, String requestId, CollaborationCallback callback) {
        try {
            JSONObject body = new JSONObject().put("role", role).put("expected_plan_version", expectedVersion).put("request_id", requestId);
            runCollaborationRequest("PUT", "/api/trips/" + tripId + "/members/" + memberUserId + "/role", body, CollaborationPayload.MEMBER_COMMAND, callback);
        } catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }
    public void removeMember(String tripId, String memberUserId, long expectedVersion, String requestId, CollaborationCallback callback) { versionedCommand("DELETE", "/api/trips/" + tripId + "/members/" + memberUserId, expectedVersion, requestId, callback); }
    public void leaveTrip(String tripId, long expectedVersion, String requestId, CollaborationCallback callback) { versionedCommand("POST", "/api/trips/" + tripId + "/leave", expectedVersion, requestId, callback); }
    public void deleteTrip(String tripId, long expectedVersion, String requestId, CollaborationCallback callback) { versionedCommand("DELETE", "/api/trips/" + tripId, expectedVersion, requestId, callback); }
    public void issueInvitation(String tripId, String token, String requestId, CollaborationCallback callback) {
        try { runCollaborationRequest("POST", "/api/trips/" + tripId + "/invitation", new JSONObject().put("invitation_token", token).put("request_id", requestId), CollaborationPayload.INVITATION, callback); }
        catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }
    public void revokeInvitation(String tripId, String requestId, CollaborationCallback callback) {
        try { runCollaborationRequest("DELETE", "/api/trips/" + tripId + "/invitation", new JSONObject().put("request_id", requestId), CollaborationPayload.EMPTY, callback); }
        catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }
    public void joinInvitation(String token, String memberLabel, String requestId, CollaborationCallback callback) {
        try { runCollaborationRequest("POST", "/api/trip-invitations/join", new JSONObject().put("invitation_token", token).put("member_label", memberLabel).put("request_id", requestId), CollaborationPayload.JOIN, callback); }
        catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }
    /** R2b foreground upload keeps only an app-private, bounded source file for ambiguous retry. */
    public void setTripCover(String tripId, CoverUpload upload, CoverCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++coverGeneration; generation = authGeneration; }
        executor.execute(() -> {
            CoverResult result;
            try { ProtectedResponse response = protectedMultipartResponse(tripId, upload, generation); result = response.authEnded() ? CoverResult.authEnded() : parseCoverCommand(response.response()); }
            catch (IOException | JSONException exception) { result = CoverResult.retryable(); }
            completeCover(request, callback, result);
        });
    }
    public void removeTripCover(String tripId, long expectedVersion, String requestId, CoverCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++coverGeneration; generation = authGeneration; }
        executor.execute(() -> {
            CoverResult result;
            try { ProtectedResponse response = protectedResponse("DELETE", "/api/trips/" + tripId + "/cover", new JSONObject().put("expected_plan_version", expectedVersion).put("request_id", requestId), generation); result = response.authEnded() ? CoverResult.authEnded() : parseCoverCommand(response.response()); }
            catch (IOException | JSONException exception) { result = CoverResult.retryable(); }
            completeCover(request, callback, result);
        });
    }
    public void coverImage(Cover cover, boolean thumbnail, CoverImageCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = coverGeneration; generation = authGeneration; }
        executor.execute(() -> {
            CoverImageResult result;
            try { ProtectedResponse response = protectedImageResponse(thumbnail ? cover.thumbnailPath() : cover.filePath(), thumbnail ? MAX_THUMBNAIL_BYTES : MAX_DISPLAY_BYTES, generation); result = response.authEnded() ? CoverImageResult.authEnded() : parseCoverImage(response.response(), thumbnail ? MAX_THUMBNAIL_BYTES : MAX_DISPLAY_BYTES); }
            catch (IOException | JSONException exception) { result = CoverImageResult.error(); }
            completeCoverImage(request, callback, result);
        });
    }
    public synchronized void cancelCoverRequests() { coverGeneration++; }

    /** R3a catalog reads and the existing basket command share the Bearer/refresh-once transport. */
    public void searchPlaces(String query, String region, String category, String cursor, PlacePageCallback callback) {
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,4096}")) { main.post(() -> callback.completed(PlacePageResult.invalidCursor())); return; }
        StringBuilder path = new StringBuilder("/api/places?query=").append(android.net.Uri.encode(query));
        if (region != null) path.append("&region=").append(android.net.Uri.encode(region));
        if (category != null) path.append("&category=").append(android.net.Uri.encode(category));
        path.append("&limit=20");
        if (cursor != null) path.append("&cursor=").append(cursor);
        runPlacePageRequest(path.toString(), callback);
    }

    public void placeDetail(String placeId, PlaceCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++placeGeneration; generation = authGeneration; }
        executor.execute(() -> {
            PlaceResult result;
            try { ProtectedResponse response = protectedResponse("GET", "/api/places/" + placeId, null, generation, MAX_PLACE_RESPONSE_BYTES); result = response.authEnded() ? PlaceResult.authEnded() : parsePlaceDetail(response.response()); }
            catch (IOException | JSONException exception) { result = PlaceResult.retryable(); }
            PlaceResult delivered = result; main.post(() -> { synchronized (AuthApiClient.this) { if (placeGeneration != request) return; } callback.completed(delivered); });
        });
    }

    public void savePlaceToBasket(String tripId, String placeId, long expectedVersion, String requestId, BasketSaveCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++placeGeneration; generation = authGeneration; }
        executor.execute(() -> {
            BasketSaveResult result;
            try {
                JSONObject body = new JSONObject().put("place_id", placeId).put("expected_plan_version", expectedVersion).put("request_id", requestId);
                ProtectedResponse response = protectedResponse("POST", "/api/trips/" + tripId + "/trip-items", body, generation);
                result = response.authEnded() ? BasketSaveResult.authEnded() : parseBasketSave(response.response());
            } catch (IOException | JSONException exception) { result = BasketSaveResult.retryable(); }
            BasketSaveResult delivered = result; main.post(() -> { synchronized (AuthApiClient.this) { if (placeGeneration != request) return; } callback.completed(delivered); });
        });
    }

    public synchronized void cancelPlaceRequests() { placeGeneration++; }

    private void runPlacePageRequest(String path, PlacePageCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++placeGeneration; generation = authGeneration; }
        executor.execute(() -> {
            PlacePageResult result;
            try { ProtectedResponse response = protectedResponse("GET", path, null, generation, MAX_PLACE_RESPONSE_BYTES); result = response.authEnded() ? PlacePageResult.authEnded() : parsePlacePage(response.response()); }
            catch (IOException | JSONException exception) { result = PlacePageResult.retryable(); }
            PlacePageResult delivered = result; main.post(() -> { synchronized (AuthApiClient.this) { if (placeGeneration != request) return; } callback.completed(delivered); });
        });
    }

    private void versionedCommand(String method, String path, long expectedVersion, String requestId, CollaborationCallback callback) {
        try { runCollaborationRequest(method, path, new JSONObject().put("expected_plan_version", expectedVersion).put("request_id", requestId), CollaborationPayload.EMPTY, callback); }
        catch (JSONException exception) { postCollaboration(callback, CollaborationResult.retryable()); }
    }
    private void runCollaborationRequest(String method, String path, JSONObject body, CollaborationPayload payload, CollaborationCallback callback) {
        final long request; final long generation;
        synchronized (this) { request = ++collaborationGeneration; generation = authGeneration; }
        executor.execute(() -> {
            CollaborationResult result;
            try { ProtectedResponse response = protectedResponse(method, path, body, generation); result = response.authEnded() ? CollaborationResult.authEnded() : parseCollaboration(response.response(), payload); }
            catch (IOException | JSONException exception) { result = CollaborationResult.retryable(); }
            CollaborationResult delivered = result;
            main.post(() -> { synchronized (AuthApiClient.this) { if (collaborationGeneration != request) return; } callback.completed(delivered); });
        });
    }
    private void postCollaboration(CollaborationCallback callback, CollaborationResult result) { main.post(() -> callback.completed(result)); }

    String accessToken() { return accessToken; }

    private void runTripRequest(String method, String path, JSONObject body, boolean requireMetadata, TripCallback callback) {
        final long request;
        final long generation;
        synchronized (this) { request = ++readGeneration; generation = authGeneration; }
        executor.execute(() -> {
            TripResult result;
            try {
                ProtectedResponse response = protectedResponse(method, path, body, generation);
                result = response.authEnded() ? TripResult.authEnded() : parseTrip(response.response(), requireMetadata);
            } catch (IOException | JSONException exception) { result = TripResult.retryable(); }
            completeTrip(request, callback, result);
        });
    }

    private void runTripPageRequest(String method, String path, JSONObject body, TripPageCallback callback) {
        final long request;
        final long generation;
        synchronized (this) { request = ++readGeneration; generation = authGeneration; }
        executor.execute(() -> {
            TripPageResult result;
            try {
                ProtectedResponse response = protectedResponse(method, path, body, generation);
                result = response.authEnded() ? TripPageResult.authEnded() : parseTripPage(response.response());
            } catch (IOException | JSONException exception) { result = TripPageResult.retryable(); }
            completeTripPage(request, callback, result);
        });
    }

    private ProtectedResponse protectedMultipartResponse(String tripId, CoverUpload upload, long generation) throws IOException, JSONException {
        String token = currentAccess(generation);
        if (token == null) return new ProtectedResponse(true, null);
        Response response = authenticatedMultipartRequest(tripId, upload, token);
        if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        String refresh = store.read();
        if (refresh == null) { clearLocal(generation); return new ProtectedResponse(true, null); }
        try {
            Tokens tokens = tokenRequest("/api/auth/refresh", new JSONObject().put("refresh_token", refresh));
            if (!accept(tokens, generation)) return new ProtectedResponse(true, null);
            response = authenticatedMultipartRequest(tripId, upload, tokens.accessToken());
            if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        } catch (IOException | JSONException | GeneralSecurityException ignored) { }
        clearLocal(generation); return new ProtectedResponse(true, null);
    }
    private ProtectedResponse protectedImageResponse(String path, int cap, long generation) throws IOException, JSONException {
        String token = currentAccess(generation);
        if (token == null) return new ProtectedResponse(true, null);
        Response response = authenticatedImageRequest(path, cap, token);
        if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        String refresh = store.read();
        if (refresh == null) { clearLocal(generation); return new ProtectedResponse(true, null); }
        try {
            Tokens tokens = tokenRequest("/api/auth/refresh", new JSONObject().put("refresh_token", refresh));
            if (!accept(tokens, generation)) return new ProtectedResponse(true, null);
            response = authenticatedImageRequest(path, cap, tokens.accessToken());
            if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        } catch (IOException | JSONException | GeneralSecurityException ignored) { }
        clearLocal(generation); return new ProtectedResponse(true, null);
    }
    private ProtectedResponse protectedResponse(String method, String path, JSONObject body, long generation)
            throws IOException, JSONException {
        return protectedResponse(method, path, body, generation, MAX_RESPONSE_BYTES);
    }

    private ProtectedResponse protectedResponse(String method, String path, JSONObject body, long generation, int responseCap)
            throws IOException, JSONException {
        String token = currentAccess(generation);
        if (token == null) return new ProtectedResponse(true, null);
        Response response = authenticatedRequest(method, path, body, token, responseCap);
        if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        String refresh = store.read();
        if (refresh == null) { clearLocal(generation); return new ProtectedResponse(true, null); }
        try {
            Tokens tokens = tokenRequest("/api/auth/refresh", new JSONObject().put("refresh_token", refresh));
            if (!accept(tokens, generation)) return new ProtectedResponse(true, null);
            response = authenticatedRequest(method, path, body, tokens.accessToken(), responseCap);
            if (response.statusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return new ProtectedResponse(false, response);
        } catch (IOException | JSONException | GeneralSecurityException ignored) { }
        clearLocal(generation);
        return new ProtectedResponse(true, null);
    }

    private Tokens tokenRequest(String path, JSONObject body) throws IOException, JSONException {
        Response response = request(path, body);
        if (response.statusCode() != HttpURLConnection.HTTP_OK) throw new IOException("Authentication request failed");
        return parseTokens(response);
    }

    private Tokens parseTokens(Response response) throws JSONException {
        JSONObject json = new JSONObject(response.body());
        if (!"Bearer".equals(json.getString("token_type"))) throw new JSONException("Unexpected token type");
        String access = json.getString("access_token");
        String refresh = json.getString("refresh_token");
        long accessExpiry = json.getLong("access_expires_in");
        long refreshExpiry = json.getLong("refresh_expires_in");
        if (access.isBlank() || refresh.isBlank() || access.length() > 4096 || refresh.length() > 4096
                || accessExpiry <= 0 || refreshExpiry <= 0) throw new JSONException("Invalid token response");
        return new Tokens(access, refresh);
    }

    private void readTripItem(long request, long generation, String tripId, String tripItemId,
            TripItemCallback callback) {
        String token = currentAccess(generation);
        if (token == null) {
            completeRead(request, callback, TripItemResult.authEnded());
            return;
        }
        try {
            Response response = get("/api/trips/" + tripId + "/trip-items/" + tripItemId, token);
            if (response.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                String refresh = store.read();
                if (refresh == null) {
                    clearLocal(generation);
                    completeRead(request, callback, TripItemResult.authEnded());
                    return;
                }
                Tokens tokens;
                try {
                    tokens = tokenRequest("/api/auth/refresh", new JSONObject().put("refresh_token", refresh));
                    if (!accept(tokens, generation)) return;
                } catch (IOException | JSONException | GeneralSecurityException ignored) {
                    clearLocal(generation);
                    completeRead(request, callback, TripItemResult.authEnded());
                    return;
                }
                response = get("/api/trips/" + tripId + "/trip-items/" + tripItemId, tokens.accessToken());
                if (response.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearLocal(generation);
                    completeRead(request, callback, TripItemResult.authEnded());
                    return;
                }
            }
            completeRead(request, callback, parseTripItem(response));
        } catch (IOException | JSONException ignored) {
            if (currentAuth(generation)) completeRead(request, callback, TripItemResult.retryable());
        }
    }

    private TripItemResult parseTripItem(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            JSONObject json = new JSONObject(response.body());
            if (json.length() != 6 || !json.has("trip_item_id") || !json.has("place_id")
                    || !json.has("state") || !json.has("schedule_date") || !json.has("sort_order")
                    || !json.has("plan_version")) throw new JSONException("Unexpected TripItem response");
            String tripItemId = canonicalUuid(json.getString("trip_item_id"));
            String placeId = canonicalUuid(json.getString("place_id"));
            String state = json.getString("state");
            String scheduleDate = json.isNull("schedule_date") ? null : validIsoDate(json.getString("schedule_date"));
            Long sortOrder = json.isNull("sort_order") ? null : strictLong(json.get("sort_order"));
            long planVersion = strictLong(json.get("plan_version"));
            boolean basket = "BASKET".equals(state) && scheduleDate == null && sortOrder == null;
            boolean scheduled = "SCHEDULED".equals(state) && scheduleDate != null
                && sortOrder != null && sortOrder > 0;
            if (planVersion < 0 || !(basket || scheduled)) throw new JSONException("Invalid TripItem response");
            return TripItemResult.success(new TripItem(tripItemId, placeId, state, scheduleDate, sortOrder, planVersion));
        }
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
            JSONObject root = new JSONObject(response.body());
            JSONObject error = root.getJSONObject("error");
            String code = error.getString("code");
            if ("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE".equals(code)) return TripItemResult.tripNotFound();
            if ("TRIP_ITEM_NOT_FOUND".equals(code)) return TripItemResult.itemNotFound();
        }
        return TripItemResult.retryable();
    }

    private CollaborationResult parseCollaboration(Response response, CollaborationPayload payload) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_OK || response.statusCode() == HttpURLConnection.HTTP_CREATED || response.statusCode() == HttpURLConnection.HTTP_NO_CONTENT) {
            if (payload == CollaborationPayload.EMPTY) return CollaborationResult.success(null, null, null, null);
            JSONObject json = new JSONObject(response.body());
            if (payload == CollaborationPayload.MEMBER_COMMAND) {
                if (json.length() != 4 || !("UPDATED".equals(requiredString(json, "result")) || "UNCHANGED".equals(requiredString(json, "result")))
                        || !("ADMIN".equals(requiredString(json, "role")) || "MEMBER".equals(requiredString(json, "role")))
                        || strictLong(json.get("plan_version")) < 0) throw new JSONException("Invalid member command response");
                return CollaborationResult.success(null, null, null, null);
            }
            if (payload == CollaborationPayload.TRIP) return CollaborationResult.success(parseTripObject(json, true, true), null, null, null);
            if (payload == CollaborationPayload.MEMBERS) return CollaborationResult.success(null, parseMemberPage(json), null, null);
            if (payload == CollaborationPayload.INVITATION) {
                if (json.length() != 3 || !"ISSUED".equals(requiredString(json, "result")) || !json.has("invite_uri") || !json.has("expires_at")) throw new JSONException("Invalid invitation response");
                String uri = requiredString(json, "invite_uri"); if (!uri.matches("stog://trip-invitations/[A-Za-z0-9_-]{43}")) throw new JSONException("Invalid invitation URI");
                return CollaborationResult.success(null, null, null, uri);
            }
            if (payload == CollaborationPayload.JOIN) {
                String result = requiredString(json, "result"); String role = requiredString(json, "role");
                if (json.length() != 5 || !("JOINED".equals(result) || "ALREADY_MEMBER".equals(result)) || !("MEMBER".equals(role) || "ADMIN".equals(role))
                        || strictLong(json.get("plan_version")) < 0 || !json.has("trip_id") || !json.has("member_label")) throw new JSONException("Invalid join response");
                canonicalUuid(requiredString(json, "trip_id"));
                if (!json.isNull("member_label")) requiredString(json, "member_label");
                return CollaborationResult.success(null, null, null, null);
            }
        }
        String code = errorCodeValue(response);
        if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST && "INVALID_CURSOR".equals(code)) return CollaborationResult.invalidCursor();
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) return "INVITATION_NOT_FOUND_OR_NOT_USABLE".equals(code) ? CollaborationResult.invitationUnusable() : CollaborationResult.tripNotFound();
        if (response.statusCode() == HttpURLConnection.HTTP_FORBIDDEN) return "ADMIN_REQUIRED".equals(code) ? CollaborationResult.adminRequired() : "ADMIN_CANNOT_LEAVE".equals(code) ? CollaborationResult.adminCannotLeave() : CollaborationResult.forbidden();
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT) {
            if ("PLAN_VERSION_CONFLICT".equals(code)) return CollaborationResult.versionConflict();
            if ("TRIP_DATE_RANGE_CONFLICT".equals(code)) return CollaborationResult.dateRangeConflict();
            if ("LAST_ADMIN_REQUIRED".equals(code)) return CollaborationResult.lastAdminRequired();
            if ("REQUEST_ID_REUSED".equals(code)) return CollaborationResult.requestIdReused();
        }
        return CollaborationResult.retryable();
    }
    private static MemberPage parseMemberPage(JSONObject root) throws JSONException {
        if (root.length() != 5 || !root.has("trip_id") || !root.has("plan_version") || !root.has("member_count") || !root.has("items") || !root.has("next_cursor")) throw new JSONException("Invalid members response");
        long version = strictLong(root.get("plan_version")); long count = strictLong(root.get("member_count")); if (version < 0 || count < 1) throw new JSONException("Invalid member page");
        org.json.JSONArray array = root.getJSONArray("items"); List<Member> members = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index); if (item.length() != 4 || !item.has("member_user_id") || !item.has("role") || !item.has("member_label") || !item.has("is_self")) throw new JSONException("Invalid member");
            String role = requiredString(item, "role"); if (!("ADMIN".equals(role) || "MEMBER".equals(role)) || !(item.get("is_self") instanceof Boolean)) throw new JSONException("Invalid member");
            members.add(new Member(canonicalUuid(requiredString(item, "member_user_id")), role, optionalString(item, "member_label"), item.getBoolean("is_self")));
        }
        return new MemberPage(canonicalUuid(requiredString(root, "trip_id")), version, count, members, optionalString(root, "next_cursor"));
    }

    private TripPageResult parseTripPage(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST && errorCode(response, "INVALID_CURSOR")) {
            return TripPageResult.invalidCursor();
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK) return TripPageResult.retryable();
        JSONObject root = new JSONObject(response.body());
        if (root.length() != 3 || !root.has("as_of_date") || !root.has("items") || !root.has("next_cursor")) {
            throw new JSONException("Unexpected trip list response");
        }
        List<Trip> trips = new ArrayList<>();
        org.json.JSONArray items = root.getJSONArray("items");
        for (int index = 0; index < items.length(); index++) trips.add(parseTripObject(items.getJSONObject(index), false, false));
        return TripPageResult.success(new TripPage(validIsoDate(requiredString(root, "as_of_date")), trips,
            optionalString(root, "next_cursor")));
    }

    private TripResult parseTrip(Response response, boolean requireMetadata) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) return TripResult.tripNotFound();
        if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST && errorCode(response, "INVALID_REQUEST")) {
            return TripResult.invalidRequest();
        }
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT && errorCode(response, "REQUEST_ID_REUSED")) {
            return TripResult.requestIdReused();
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK && response.statusCode() != HttpURLConnection.HTTP_CREATED) {
            return TripResult.retryable();
        }
        return TripResult.success(parseTripObject(new JSONObject(response.body()), true, requireMetadata));
    }

    private static PlacePageResult parsePlacePage(Response response) throws JSONException {
        String code = errorCodeValue(response);
        if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST && "INVALID_CURSOR".equals(code)) return PlacePageResult.invalidCursor();
        if (response.statusCode() != HttpURLConnection.HTTP_OK) return PlacePageResult.retryable();
        JSONObject root = new JSONObject(response.body());
        if (root.length() != 2 || !root.has("items") || !root.has("next_cursor")) throw new JSONException("Unexpected place page");
        org.json.JSONArray array = root.getJSONArray("items"); List<Place> places = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) places.add(parsePlace(array.getJSONObject(index), false));
        String cursor = optionalString(root, "next_cursor");
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,4096}")) throw new JSONException("Invalid place cursor");
        return PlacePageResult.success(new PlacePage(places, cursor));
    }

    private static PlaceResult parsePlaceDetail(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND && "PLACE_NOT_FOUND".equals(errorCodeValue(response))) return PlaceResult.notFound();
        if (response.statusCode() != HttpURLConnection.HTTP_OK) return PlaceResult.retryable();
        return PlaceResult.success(parsePlace(new JSONObject(response.body()), true));
    }

    private static Place parsePlace(JSONObject json, boolean detail) throws JSONException {
        int fields = detail ? 9 : 7;
        if (json.length() != fields || !json.has("place_id") || !json.has("name") || !json.has("category")
                || !json.has("region_label") || !json.has("address") || !json.has("latitude") || !json.has("longitude")
                || (detail && (!json.has("road_address") || !json.has("description")))) throw new JSONException("Unexpected place");
        String name = requiredString(json, "name"); if (name.isBlank()) throw new JSONException("Invalid place name");
        Double latitude = optionalDecimal(json, "latitude"); Double longitude = optionalDecimal(json, "longitude");
        if ((latitude == null) != (longitude == null) || (latitude != null && (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180))) throw new JSONException("Invalid coordinates");
        return new Place(canonicalUuid(requiredString(json, "place_id")), name, optionalString(json, "category"),
            optionalString(json, "region_label"), optionalString(json, "address"), detail ? optionalString(json, "road_address") : null,
            detail ? optionalString(json, "description") : null, latitude, longitude);
    }

    private static Double optionalDecimal(JSONObject json, String key) throws JSONException {
        if (json.isNull(key)) return null; Object value = json.get(key);
        if (!(value instanceof Number)) throw new JSONException("Invalid decimal");
        double number = ((Number) value).doubleValue(); if (!Double.isFinite(number)) throw new JSONException("Invalid decimal"); return number;
    }

    private static BasketSaveResult parseBasketSave(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_OK || response.statusCode() == HttpURLConnection.HTTP_CREATED) {
            JSONObject json = new JSONObject(response.body());
            if (json.length() != 4 || !json.has("result") || !json.has("trip_item_id") || !json.has("place_id") || !json.has("plan_version")) throw new JSONException("Unexpected basket save");
            String result = requiredString(json, "result"); long version = strictLong(json.get("plan_version"));
            if (version < 0 || !("CREATED".equals(result) || "ALREADY_EXISTS".equals(result))) throw new JSONException("Invalid basket save");
            BasketSave saved = new BasketSave(canonicalUuid(requiredString(json, "trip_item_id")), canonicalUuid(requiredString(json, "place_id")), version);
            return "CREATED".equals(result) ? BasketSaveResult.created(saved) : BasketSaveResult.alreadyExists(saved);
        }
        String code = errorCodeValue(response);
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT && "PLAN_VERSION_CONFLICT".equals(code)) return BasketSaveResult.versionConflict();
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT && "TRIP_NOT_ELIGIBLE".equals(code)) return BasketSaveResult.tripEnded();
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND && "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE".equals(code)) return BasketSaveResult.tripInaccessible();
        return BasketSaveResult.retryable();
    }

    private BasketResult parseBasket(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST && errorCode(response, "INVALID_CURSOR")) {
            return BasketResult.invalidCursor();
        }
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) return BasketResult.tripNotFound();
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT && errorCode(response, "PLAN_VERSION_CONFLICT")) {
            return BasketResult.versionConflict();
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK) return BasketResult.retryable();
        JSONObject root = new JSONObject(response.body());
        if (root.length() != 4 || !root.has("trip_id") || !root.has("plan_version") || !root.has("items")
                || !root.has("next_cursor")) throw new JSONException("Unexpected basket response");
        long version = strictLong(root.get("plan_version"));
        if (version < 0) throw new JSONException("Invalid basket version");
        List<BasketItem> items = new ArrayList<>();
        org.json.JSONArray array = root.getJSONArray("items");
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            if (item.length() != 6 || !item.has("trip_item_id") || !item.has("place_id") || !item.has("state")
                    || !item.has("schedule_date") || !item.has("sort_order") || !item.has("created_at")
                    || !"BASKET".equals(requiredString(item, "state")) || !item.isNull("schedule_date")
                    || !item.isNull("sort_order")) {
                throw new JSONException("Invalid basket item");
            }
            String createdAt = requiredString(item, "created_at");
            if (!createdAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z")) {
                throw new JSONException("Invalid basket timestamp");
            }
            items.add(new BasketItem(canonicalUuid(requiredString(item, "trip_item_id")),
                canonicalUuid(requiredString(item, "place_id")), createdAt));
        }
        return BasketResult.success(new BasketPage(canonicalUuid(requiredString(root, "trip_id")), version, items,
            optionalString(root, "next_cursor")));
    }

    private static Trip parseTripObject(JSONObject json, boolean includesAsOfDate, boolean requireMetadata) throws JSONException {
        int expectedFields = includesAsOfDate ? 11 : 10;
        if (json.length() != expectedFields || !json.has("trip_id") || !json.has("name") || !json.has("region_label")
                || !json.has("start_date") || !json.has("end_date") || !json.has("status") || !json.has("role")
                || !json.has("member_count") || !json.has("plan_version") || !json.has("cover") || (includesAsOfDate && !json.has("as_of_date"))) {
            throw new JSONException("Unexpected trip response");
        }
        if (includesAsOfDate) validIsoDate(requiredString(json, "as_of_date"));
        String status = requiredString(json, "status");
        String role = requiredString(json, "role");
        long members = strictLong(json.get("member_count"));
        long version = strictLong(json.get("plan_version"));
        if (!("ONGOING".equals(status) || "UPCOMING".equals(status) || "PAST".equals(status))
                || !("ADMIN".equals(role) || "MEMBER".equals(role)) || members < 1 || version < 0) {
            throw new JSONException("Invalid trip fields");
        }
        String name = requireMetadata ? requiredString(json, "name") : optionalString(json, "name");
        String region = requireMetadata ? requiredString(json, "region_label") : optionalString(json, "region_label");
        return new Trip(canonicalUuid(requiredString(json, "trip_id")), name, region,
            validIsoDate(requiredString(json, "start_date")), validIsoDate(requiredString(json, "end_date")),
            status, role, members, version, parseCover(json));
    }

    private static Cover parseCover(JSONObject trip) throws JSONException {
        if (trip.isNull("cover")) return null;
        JSONObject value = trip.getJSONObject("cover");
        if (value.length() != 10 || !value.has("cover_id") || !value.has("media_type") || !value.has("byte_size") || !value.has("width") || !value.has("height") || !value.has("thumbnail_byte_size") || !value.has("thumbnail_width") || !value.has("thumbnail_height") || !value.has("file_path") || !value.has("thumbnail_path")) throw new JSONException("Invalid cover");
        long bytes = strictLong(value.get("byte_size")); long thumbnailBytes = strictLong(value.get("thumbnail_byte_size")); long width = strictLong(value.get("width")); long height = strictLong(value.get("height")); long thumbnailWidth = strictLong(value.get("thumbnail_width")); long thumbnailHeight = strictLong(value.get("thumbnail_height"));
        String id = canonicalUuid(requiredString(value, "cover_id")); String file = requiredString(value, "file_path"); String thumbnail = requiredString(value, "thumbnail_path");
        if (!"image/jpeg".equals(requiredString(value, "media_type")) || bytes < 1 || bytes > MAX_DISPLAY_BYTES || thumbnailBytes < 1 || thumbnailBytes > MAX_THUMBNAIL_BYTES || width < 1 || width > 2048 || height < 1 || height > 2048 || thumbnailWidth < 1 || thumbnailWidth > 512 || thumbnailHeight < 1 || thumbnailHeight > 512 || !file.matches("/api/trips/[0-9a-f-]{36}/covers/[0-9a-f-]{36}/file") || !thumbnail.matches("/api/trips/[0-9a-f-]{36}/covers/[0-9a-f-]{36}/thumbnail")) throw new JSONException("Invalid cover");
        return new Cover(id, bytes, (int) width, (int) height, thumbnailBytes, (int) thumbnailWidth, (int) thumbnailHeight, file, thumbnail);
    }
    private static String requiredString(JSONObject json, String key) throws JSONException {
        if (json.isNull(key) || !(json.get(key) instanceof String)) throw new JSONException("Invalid string");
        return (String) json.get(key);
    }

    private static String optionalString(JSONObject json, String key) throws JSONException {
        if (json.isNull(key)) return null;
        return requiredString(json, key);
    }

    private static boolean errorCode(Response response, String expected) throws JSONException {
        JSONObject root = new JSONObject(response.body());
        return root.length() == 1 && root.has("error") && expected.equals(root.getJSONObject("error").getString("code"));
    }
    private static String errorCodeValue(Response response) {
        try { JSONObject root = new JSONObject(response.body()); return root.has("error") ? root.getJSONObject("error").optString("code", "") : ""; }
        catch (JSONException exception) { return ""; }
    }

    private static String canonicalUuid(String value) throws JSONException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new JSONException("Invalid UUID response");
        }
    }

    private static String validIsoDate(String value) throws JSONException {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new JSONException("Invalid date response");
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        java.util.Date parsed = parser.parse(value, position);
        if (parsed == null || position.getIndex() != value.length() || position.getErrorIndex() >= 0
                || !parser.format(parsed).equals(value)) throw new JSONException("Invalid date response");
        return value;
    }

    private static long strictLong(Object value) throws JSONException {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new JSONException("Invalid integer response");
        }
        return ((Number) value).longValue();
    }

    private Response authenticatedMultipartRequest(String tripId, CoverUpload upload, String bearer) throws IOException, JSONException {
        if (!upload.file().isFile() || upload.file().length() < 1 || upload.file().length() > 8L * 1024L * 1024L || !("image/jpeg".equals(upload.mediaType()) || "image/png".equals(upload.mediaType()))) throw new IOException("Invalid cover source");
        String boundary = "stog-" + UUID.randomUUID();
        byte[] imageHeader = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cover\"\r\nContent-Type: " + upload.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] command = new JSONObject().put("expected_plan_version", upload.expectedPlanVersion()).put("request_id", upload.requestId()).toString().getBytes(StandardCharsets.UTF_8);
        byte[] commandHeader = ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"command\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUri + "/api/trips/" + tripId + "/cover").toURL().openConnection(); activeConnection = connection;
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS); connection.setReadTimeout(TIMEOUT_MILLIS); connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Authorization", "Bearer " + bearer); connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary); connection.setUseCaches(false); connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode((long) imageHeader.length + upload.file().length() + commandHeader.length + command.length + end.length);
            try (OutputStream output = connection.getOutputStream(); InputStream input = new FileInputStream(upload.file())) { output.write(imageHeader); byte[] buffer = new byte[8192]; for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read); output.write(commandHeader); output.write(command); output.write(end); }
            int status = connection.getResponseCode(); InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream(); return new Response(status, stream == null ? "" : readLimited(stream));
        } finally { activeConnection = null; connection.disconnect(); }
    }
    private Response authenticatedImageRequest(String path, int cap, String bearer) throws IOException {
        if (!path.matches("/api/trips/[0-9a-f-]{36}/covers/[0-9a-f-]{36}/(file|thumbnail)")) throw new IOException("Invalid cover path");
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUri + path).toURL().openConnection(); activeConnection = connection;
        try { connection.setConnectTimeout(TIMEOUT_MILLIS); connection.setReadTimeout(TIMEOUT_MILLIS); connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "image/jpeg"); connection.setRequestProperty("Authorization", "Bearer " + bearer); connection.setUseCaches(false); int status = connection.getResponseCode(); InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream(); return status >= 400 ? new Response(status, stream == null ? "" : readLimited(stream)) : new Response(status, "", stream == null ? null : readLimitedBytes(stream, cap)); }
        finally { activeConnection = null; connection.disconnect(); }
    }
    private Response authenticatedRequest(String method, String path, JSONObject body, String bearer) throws IOException {
        return authenticatedRequest(method, path, body, bearer, MAX_RESPONSE_BYTES);
    }
    private Response authenticatedRequest(String method, String path, JSONObject body, String bearer, int responseCap) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUri + path).toURL().openConnection();
        activeConnection = connection;
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setUseCaches(false);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readLimited(stream, responseCap));
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private Response get(String path, String bearer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUri + path).toURL().openConnection();
        activeConnection = connection;
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readLimited(stream));
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private Response request(String path, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUri + path).toURL().openConnection();
        activeConnection = connection;
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return new Response(status, "");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readLimited(stream));
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private static CoverResult parseCoverCommand(Response response) throws JSONException {
        if (response.statusCode() == HttpURLConnection.HTTP_OK || response.statusCode() == HttpURLConnection.HTTP_CREATED) {
            JSONObject json = new JSONObject(response.body()); String result = requiredString(json, "result"); long version = strictLong(json.get("plan_version"));
            if (version >= 0 && "SET".equals(result) && json.length() == 3 && json.has("cover")) return CoverResult.success(parseCover(json), version);
            if (version >= 0 && ("REMOVED".equals(result) || "UNCHANGED".equals(result)) && json.length() == 2) return CoverResult.success(null, version);
        }
        String code = errorCodeValue(response);
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND && "COVER_NOT_FOUND".equals(code)) return CoverResult.missing();
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT && "PLAN_VERSION_CONFLICT".equals(code)) return CoverResult.versionConflict();
        return CoverResult.retryable();
    }
    private static CoverImageResult parseCoverImage(Response response, int cap) {
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) return CoverImageResult.missing();
        if (response.statusCode() != HttpURLConnection.HTTP_OK || response.bytes() == null || response.bytes().length < 1 || response.bytes().length > cap) return CoverImageResult.error();
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(response.bytes(), 0, response.bytes().length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return CoverImageResult.error();
        int sample = 1; while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inSampleSize = sample; decode.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(response.bytes(), 0, response.bytes().length, decode);
        return bitmap == null ? CoverImageResult.error() : CoverImageResult.content(bitmap);
    }
    private static byte[] readLimitedBytes(InputStream stream, int cap) throws IOException { try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int total = 0; for (int count; (count = stream.read(buffer)) != -1;) { total += count; if (total > cap) throw new IOException("Cover too large"); output.write(buffer, 0, count); } return output.toByteArray(); } }
    private static String readLimited(InputStream stream) throws IOException { return readLimited(stream, MAX_RESPONSE_BYTES); }
    private static String readLimited(InputStream stream, int cap) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            for (int count; (count = stream.read(buffer)) != -1;) {
                total += count;
                if (total > cap) throw new IOException("Response is too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private synchronized boolean accept(Tokens tokens, long generation) throws GeneralSecurityException {
        if (authGeneration != generation) return false;
        store.write(tokens.refreshToken());
        if (authGeneration != generation) {
            store.clear();
            return false;
        }
        accessToken = tokens.accessToken();
        return true;
    }

    private synchronized String currentAccess(long generation) {
        return authGeneration == generation ? accessToken : null;
    }

    private synchronized boolean currentAuth(long generation) { return authGeneration == generation; }

    private synchronized void clearLocal(long generation) {
        if (authGeneration != generation) return;
        authGeneration++;
        accessToken = null;
        store.clear();
    }

    private synchronized void clearLocalUnconditionally() {
        accessToken = null;
        store.clear();
    }

    private void completeCover(long request, CoverCallback callback, CoverResult result) { main.post(() -> { synchronized (AuthApiClient.this) { if (coverGeneration != request) return; } callback.completed(result); }); }
    private void completeCoverImage(long request, CoverImageCallback callback, CoverImageResult result) { main.post(() -> { synchronized (AuthApiClient.this) { if (coverGeneration != request) { if (result.bitmap() != null) result.bitmap().recycle(); return; } } callback.completed(result); }); }
    private void completeTrip(long request, TripCallback callback, TripResult result) {
        main.post(() -> { synchronized (AuthApiClient.this) { if (readGeneration != request) return; } callback.completed(result); });
    }

    private void completeTripPage(long request, TripPageCallback callback, TripPageResult result) {
        main.post(() -> { synchronized (AuthApiClient.this) { if (readGeneration != request) return; } callback.completed(result); });
    }

    private void completeBasket(long request, BasketCallback callback, BasketResult result) {
        main.post(() -> { synchronized (AuthApiClient.this) { if (readGeneration != request) return; } callback.completed(result); });
    }

    private void completeRead(long request, TripItemCallback callback, TripItemResult result) {
        main.post(() -> {
            synchronized (AuthApiClient.this) {
                if (readGeneration != request) return;
            }
            callback.completed(result);
        });
    }

    private void completeNativeAuth(long generation, AuthStateMachine.AuthCallback callback,
            boolean succeeded, String errorCode) {
        main.post(() -> {
            synchronized (AuthApiClient.this) {
                if (authGeneration != generation) return;
            }
            callback.completed(succeeded, succeeded ? null : errorCode);
        });
    }

    private void complete(AuthStateMachine.CompletionCallback callback, boolean succeeded) {
        main.post(() -> callback.completed(succeeded));
    }

    @Override public void close() {
        synchronized (this) {
            authGeneration++;
            readGeneration++;
            collaborationGeneration++;
            placeGeneration++;
            accessToken = null;
        }
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
    }

    public interface TripItemCallback { void completed(TripItemResult result); }
    public interface CollaborationCallback { void completed(CollaborationResult result); }
    public interface CoverCallback { void completed(CoverResult result); }
    public interface CoverImageCallback { void completed(CoverImageResult result); }
    public interface TripCallback { void completed(TripResult result); }
    public interface TripPageCallback { void completed(TripPageResult result); }
    public interface DisplayNameCallback { void completed(String displayName); }
    public interface BasketCallback { void completed(BasketResult result); }
    public interface PlacePageCallback { void completed(PlacePageResult result); }
    public interface PlaceCallback { void completed(PlaceResult result); }
    public interface BasketSaveCallback { void completed(BasketSaveResult result); }

    public enum PlaceOutcome { SUCCESS, NOT_FOUND, INVALID_CURSOR, RETRYABLE_ERROR, AUTH_ENDED }
    public record Place(String placeId, String name, String category, String regionLabel, String address,
            String roadAddress, String description, Double latitude, Double longitude) {}
    public record PlacePage(List<Place> items, String nextCursor) {}
    public record PlacePageResult(PlaceOutcome outcome, PlacePage page) {
        static PlacePageResult success(PlacePage page) { return new PlacePageResult(PlaceOutcome.SUCCESS, page); }
        static PlacePageResult invalidCursor() { return new PlacePageResult(PlaceOutcome.INVALID_CURSOR, null); }
        static PlacePageResult retryable() { return new PlacePageResult(PlaceOutcome.RETRYABLE_ERROR, null); }
        static PlacePageResult authEnded() { return new PlacePageResult(PlaceOutcome.AUTH_ENDED, null); }
    }
    public record PlaceResult(PlaceOutcome outcome, Place place) {
        static PlaceResult success(Place place) { return new PlaceResult(PlaceOutcome.SUCCESS, place); }
        static PlaceResult notFound() { return new PlaceResult(PlaceOutcome.NOT_FOUND, null); }
        static PlaceResult retryable() { return new PlaceResult(PlaceOutcome.RETRYABLE_ERROR, null); }
        static PlaceResult authEnded() { return new PlaceResult(PlaceOutcome.AUTH_ENDED, null); }
    }
    public enum BasketSaveOutcome { CREATED, ALREADY_EXISTS, VERSION_CONFLICT, TRIP_ENDED, TRIP_INACCESSIBLE, RETRYABLE_ERROR, AUTH_ENDED }
    public record BasketSave(String tripItemId, String placeId, long planVersion) {}
    public record BasketSaveResult(BasketSaveOutcome outcome, BasketSave saved) {
        static BasketSaveResult created(BasketSave saved) { return new BasketSaveResult(BasketSaveOutcome.CREATED, saved); }
        static BasketSaveResult alreadyExists(BasketSave saved) { return new BasketSaveResult(BasketSaveOutcome.ALREADY_EXISTS, saved); }
        static BasketSaveResult versionConflict() { return new BasketSaveResult(BasketSaveOutcome.VERSION_CONFLICT, null); }
        static BasketSaveResult tripEnded() { return new BasketSaveResult(BasketSaveOutcome.TRIP_ENDED, null); }
        static BasketSaveResult tripInaccessible() { return new BasketSaveResult(BasketSaveOutcome.TRIP_INACCESSIBLE, null); }
        static BasketSaveResult retryable() { return new BasketSaveResult(BasketSaveOutcome.RETRYABLE_ERROR, null); }
        static BasketSaveResult authEnded() { return new BasketSaveResult(BasketSaveOutcome.AUTH_ENDED, null); }
    }

    public enum TripOutcome { SUCCESS, TRIP_NOT_FOUND, INVALID_REQUEST, INVALID_CURSOR, REQUEST_ID_REUSED, RETRYABLE_ERROR, AUTH_ENDED }
    public enum CollaborationOutcome { SUCCESS, INVALID_CURSOR, TRIP_NOT_FOUND, INVITATION_UNUSABLE, ADMIN_REQUIRED, ADMIN_CANNOT_LEAVE, FORBIDDEN, VERSION_CONFLICT, DATE_RANGE_CONFLICT, LAST_ADMIN_REQUIRED, REQUEST_ID_REUSED, RETRYABLE_ERROR, AUTH_ENDED }
    public record TripUpdateRequest(String name, String regionLabel, String startDate, String endDate, long expectedPlanVersion, String requestId) {}
    public record Member(String memberUserId, String role, String memberLabel, boolean self) {}
    public record MemberPage(String tripId, long planVersion, long memberCount, List<Member> items, String nextCursor) {}
    public record CollaborationResult(CollaborationOutcome outcome, Trip trip, MemberPage members, Member member, String inviteUri) {
        static CollaborationResult success(Trip trip, MemberPage members, Member member, String inviteUri) { return new CollaborationResult(CollaborationOutcome.SUCCESS, trip, members, member, inviteUri); }
        static CollaborationResult invalidCursor() { return new CollaborationResult(CollaborationOutcome.INVALID_CURSOR, null, null, null, null); }
        static CollaborationResult tripNotFound() { return new CollaborationResult(CollaborationOutcome.TRIP_NOT_FOUND, null, null, null, null); }
        static CollaborationResult invitationUnusable() { return new CollaborationResult(CollaborationOutcome.INVITATION_UNUSABLE, null, null, null, null); }
        static CollaborationResult adminRequired() { return new CollaborationResult(CollaborationOutcome.ADMIN_REQUIRED, null, null, null, null); }
        static CollaborationResult adminCannotLeave() { return new CollaborationResult(CollaborationOutcome.ADMIN_CANNOT_LEAVE, null, null, null, null); }
        static CollaborationResult forbidden() { return new CollaborationResult(CollaborationOutcome.FORBIDDEN, null, null, null, null); }
        static CollaborationResult versionConflict() { return new CollaborationResult(CollaborationOutcome.VERSION_CONFLICT, null, null, null, null); }
        static CollaborationResult dateRangeConflict() { return new CollaborationResult(CollaborationOutcome.DATE_RANGE_CONFLICT, null, null, null, null); }
        static CollaborationResult lastAdminRequired() { return new CollaborationResult(CollaborationOutcome.LAST_ADMIN_REQUIRED, null, null, null, null); }
        static CollaborationResult requestIdReused() { return new CollaborationResult(CollaborationOutcome.REQUEST_ID_REUSED, null, null, null, null); }
        static CollaborationResult retryable() { return new CollaborationResult(CollaborationOutcome.RETRYABLE_ERROR, null, null, null, null); }
        static CollaborationResult authEnded() { return new CollaborationResult(CollaborationOutcome.AUTH_ENDED, null, null, null, null); }
    }
    private enum CollaborationPayload { TRIP, MEMBERS, MEMBER_COMMAND, INVITATION, JOIN, EMPTY }
    public enum BasketOutcome { SUCCESS, TRIP_NOT_FOUND, INVALID_CURSOR, VERSION_CONFLICT, RETRYABLE_ERROR, AUTH_ENDED }
    public record TripCreateRequest(String name, String regionLabel, String startDate, String endDate, String requestId) {}
    public record Cover(String coverId, long byteSize, int width, int height, long thumbnailByteSize, int thumbnailWidth, int thumbnailHeight, String filePath, String thumbnailPath) {}
    public record CoverUpload(File file, String mediaType, long expectedPlanVersion, String requestId) {}
    public enum CoverOutcome { SUCCESS, MISSING, VERSION_CONFLICT, RETRYABLE_ERROR, AUTH_ENDED }
    public record CoverResult(CoverOutcome outcome, Cover cover, long planVersion) { static CoverResult success(Cover cover, long version) { return new CoverResult(CoverOutcome.SUCCESS, cover, version); } static CoverResult missing() { return new CoverResult(CoverOutcome.MISSING, null, -1); } static CoverResult versionConflict() { return new CoverResult(CoverOutcome.VERSION_CONFLICT, null, -1); } static CoverResult retryable() { return new CoverResult(CoverOutcome.RETRYABLE_ERROR, null, -1); } static CoverResult authEnded() { return new CoverResult(CoverOutcome.AUTH_ENDED, null, -1); } }
    public enum CoverImageOutcome { CONTENT, MISSING, ERROR, AUTH_ENDED }
    public record CoverImageResult(CoverImageOutcome outcome, Bitmap bitmap) { static CoverImageResult content(Bitmap value) { return new CoverImageResult(CoverImageOutcome.CONTENT, value); } static CoverImageResult missing() { return new CoverImageResult(CoverImageOutcome.MISSING, null); } static CoverImageResult error() { return new CoverImageResult(CoverImageOutcome.ERROR, null); } static CoverImageResult authEnded() { return new CoverImageResult(CoverImageOutcome.AUTH_ENDED, null); } }
    public record Trip(String tripId, String name, String regionLabel, String startDate, String endDate,
            String status, String role, long memberCount, long planVersion, Cover cover) {}
    public record TripPage(String asOfDate, List<Trip> items, String nextCursor) {}
    public record BasketItem(String tripItemId, String placeId, String createdAt) {}
    public record BasketPage(String tripId, long planVersion, List<BasketItem> items, String nextCursor) {}
    public record TripResult(TripOutcome outcome, Trip trip) {
        static TripResult success(Trip trip) { return new TripResult(TripOutcome.SUCCESS, trip); }
        static TripResult tripNotFound() { return new TripResult(TripOutcome.TRIP_NOT_FOUND, null); }
        static TripResult invalidRequest() { return new TripResult(TripOutcome.INVALID_REQUEST, null); }
        static TripResult requestIdReused() { return new TripResult(TripOutcome.REQUEST_ID_REUSED, null); }
        static TripResult retryable() { return new TripResult(TripOutcome.RETRYABLE_ERROR, null); }
        static TripResult authEnded() { return new TripResult(TripOutcome.AUTH_ENDED, null); }
    }
    public record TripPageResult(TripOutcome outcome, TripPage page) {
        static TripPageResult success(TripPage page) { return new TripPageResult(TripOutcome.SUCCESS, page); }
        static TripPageResult invalidCursor() { return new TripPageResult(TripOutcome.INVALID_CURSOR, null); }
        static TripPageResult retryable() { return new TripPageResult(TripOutcome.RETRYABLE_ERROR, null); }
        static TripPageResult authEnded() { return new TripPageResult(TripOutcome.AUTH_ENDED, null); }
    }
    public record BasketResult(BasketOutcome outcome, BasketPage page) {
        static BasketResult success(BasketPage page) { return new BasketResult(BasketOutcome.SUCCESS, page); }
        static BasketResult tripNotFound() { return new BasketResult(BasketOutcome.TRIP_NOT_FOUND, null); }
        static BasketResult invalidCursor() { return new BasketResult(BasketOutcome.INVALID_CURSOR, null); }
        static BasketResult versionConflict() { return new BasketResult(BasketOutcome.VERSION_CONFLICT, null); }
        static BasketResult retryable() { return new BasketResult(BasketOutcome.RETRYABLE_ERROR, null); }
        static BasketResult authEnded() { return new BasketResult(BasketOutcome.AUTH_ENDED, null); }
    }

    public enum TripItemOutcome { SUCCESS, TRIP_NOT_FOUND, ITEM_NOT_FOUND, RETRYABLE_ERROR, AUTH_ENDED }

    public record TripItem(String tripItemId, String placeId, String state, String scheduleDate,
            Long sortOrder, long planVersion) {}

    public record TripItemResult(TripItemOutcome outcome, TripItem item) {
        static TripItemResult success(TripItem item) { return new TripItemResult(TripItemOutcome.SUCCESS, item); }
        static TripItemResult tripNotFound() { return new TripItemResult(TripItemOutcome.TRIP_NOT_FOUND, null); }
        static TripItemResult itemNotFound() { return new TripItemResult(TripItemOutcome.ITEM_NOT_FOUND, null); }
        static TripItemResult retryable() { return new TripItemResult(TripItemOutcome.RETRYABLE_ERROR, null); }
        static TripItemResult authEnded() { return new TripItemResult(TripItemOutcome.AUTH_ENDED, null); }
    }

    private record Tokens(String accessToken, String refreshToken) {}
    private record Response(int statusCode, String body, byte[] bytes) { Response(int statusCode, String body) { this(statusCode, body, null); } }
    private record ProtectedResponse(boolean authEnded, Response response) {}
}
