package com.stog.app.auth;

import android.app.UiModeManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.MainActivity;
import com.stog.app.MainFragment;
import com.stog.app.R;
import com.stog.app.trip.TripViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Two normal synthetic-provider users through the real security filter and R2a product surface. */
@RunWith(AndroidJUnit4.class)
public final class RealBackendTripCollaborationUiTest {
    private AuthApiClient owner;
    private AuthApiClient member;

    @After public void cleanup() {
        AuthViewModel.clearDebugFixture();
        if (owner != null) owner.close();
        if (member != null) member.close();
    }

    @Test public void twoUsersShareEditManageRevokeAndLoseAccess() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String ownerCredential = required(arguments, "ownerProviderToken");
        String ownerUiCredential = required(arguments, "ownerUiProviderToken");
        String ownerMemberUiCredential = required(arguments, "ownerMemberUiProviderToken");
        String memberCredential = required(arguments, "memberProviderToken");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        URI backend = URI.create("http://127.0.0.1:8080");
        owner = new AuthApiClient(backend, new SecureRefreshStore(context));
        member = new AuthApiClient(backend, new SecureRefreshStore(context));
        exchange(owner, ownerCredential);
        exchange(member, memberCredential);

        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(5);
        AuthApiClient.Trip trip = create(owner, "R2a 실제 공동 여행", start);
        trip = update(owner, trip, "R2a 관리자 편집");
        final AuthApiClient.Trip staleOwnerTrip = trip;
        String firstToken = issue(owner, trip.tripId());
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS,
            collaboration(callback -> member.joinInvitation(firstToken, "두번째 구성원", id(), callback)).outcome());
        final String tripId = trip.tripId();
        AuthApiClient.Trip memberTrip = update(member, detail(member, tripId), "R2a 구성원 편집");
        assertEquals(AuthApiClient.CollaborationOutcome.VERSION_CONFLICT, collaboration(callback -> owner.updateTrip(tripId,
            new AuthApiClient.TripUpdateRequest("오래된 관리자 편집", staleOwnerTrip.regionLabel(), staleOwnerTrip.startDate(),
                staleOwnerTrip.endDate(), staleOwnerTrip.planVersion(), id()), callback)).outcome());
        assertEquals("R2a 구성원 편집", detail(owner, tripId).name());

        AuthApiClient.MemberPage page = collaboration(callback -> owner.members(tripId, null, callback)).members();
        assertEquals(2, page.memberCount());
        AuthApiClient.Member ownerMember = page.items().stream().filter(AuthApiClient.Member::self).findFirst().orElseThrow();
        AuthApiClient.Member other = page.items().stream().filter(value -> !value.self()).findFirst().orElseThrow();
        final long initialMemberVersion = page.planVersion();
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            owner.updateMemberRole(tripId, other.memberUserId(), "ADMIN", initialMemberVersion, id(), callback)).outcome());

        showOwnerUi(context, ownerUiCredential, tripId);
        final long adminLeaveVersion = detail(owner, tripId).planVersion();
        assertEquals(AuthApiClient.CollaborationOutcome.ADMIN_CANNOT_LEAVE, collaboration(callback ->
            owner.leaveTrip(tripId, adminLeaveVersion, id(), callback)).outcome());
        page = collaboration(callback -> owner.members(tripId, null, callback)).members();
        final long twoAdminVersion = page.planVersion();
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            owner.updateMemberRole(tripId, ownerMember.memberUserId(), "MEMBER", twoAdminVersion, id(), callback)).outcome());
        AuthApiClient.Trip ownerAsMember = detail(owner, tripId);
        assertEquals("MEMBER", ownerAsMember.role());
        assertEquals(AuthApiClient.CollaborationOutcome.ADMIN_REQUIRED, collaboration(callback ->
            owner.updateMemberRole(tripId, other.memberUserId(), "MEMBER", ownerAsMember.planVersion(), id(), callback)).outcome());
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            owner.leaveTrip(tripId, ownerAsMember.planVersion(), id(), callback)).outcome());
        assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND, awaitTrip(callback -> owner.tripDetail(tripId, callback)).outcome());

        page = collaboration(callback -> member.members(tripId, null, callback)).members();
        AuthApiClient.Member memberSelf = page.items().stream().filter(AuthApiClient.Member::self).findFirst().orElseThrow();
        final long soleAdminVersion = page.planVersion();
        assertEquals(AuthApiClient.CollaborationOutcome.LAST_ADMIN_REQUIRED, collaboration(callback ->
            member.updateMemberRole(tripId, memberSelf.memberUserId(), "MEMBER", soleAdminVersion, id(), callback)).outcome());

        String reissuedOldToken = issue(member, tripId);
        String reissuedCurrentToken = issue(member, tripId);
        assertEquals(AuthApiClient.CollaborationOutcome.INVITATION_UNUSABLE,
            collaboration(callback -> owner.joinInvitation(reissuedOldToken, "다시 참여", id(), callback)).outcome());
        assertEquals(AuthApiClient.TripOutcome.SUCCESS, awaitTrip(callback -> member.tripDetail(tripId, callback)).outcome());
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS,
            collaboration(callback -> member.revokeInvitation(tripId, id(), callback)).outcome());
        assertEquals(AuthApiClient.CollaborationOutcome.INVITATION_UNUSABLE,
            collaboration(callback -> owner.joinInvitation(reissuedCurrentToken, "다시 참여", id(), callback)).outcome());
        String restartToken = issue(member, tripId);
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS,
            collaboration(callback -> owner.joinInvitation(restartToken, "다시 참여", id(), callback)).outcome());

        assertMalformedInvitationSurface(backend, member.accessToken());
        String pendingToken = issue(member, tripId);
        showMemberUiAndClearPendingOnLogout(context, ownerMemberUiCredential, tripId, pendingToken);

        page = collaboration(callback -> member.members(tripId, null, callback)).members();
        AuthApiClient.Member rejoinedOwner = page.items().stream().filter(value -> !value.self()).findFirst().orElseThrow();
        final long removalVersion = page.planVersion();
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            member.removeMember(tripId, rejoinedOwner.memberUserId(), removalVersion, id(), callback)).outcome());
        assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND, awaitTrip(callback -> owner.tripDetail(tripId, callback)).outcome());
        AuthApiClient.Trip current = detail(member, tripId);
        String deleteRequest = id();
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            member.deleteTrip(tripId, current.planVersion(), deleteRequest, callback)).outcome());
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
            member.deleteTrip(tripId, current.planVersion(), deleteRequest, callback)).outcome());
        assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND, awaitTrip(callback -> member.tripDetail(tripId, callback)).outcome());
        assertEquals(AuthApiClient.CollaborationOutcome.INVITATION_UNUSABLE,
            collaboration(callback -> owner.joinInvitation(pendingToken, "삭제 뒤 참여", id(), callback)).outcome());
    }

    private void showOwnerUi(Context context, String credential, String tripId) throws Exception {
        new SecureRefreshStore(context).clear();
        MutableKakao kakao = new MutableKakao(credential);
        AuthViewModel.installDebugFixture(app -> {
            AuthApiClient api = new AuthApiClient(URI.create("http://127.0.0.1:8080"), new SecureRefreshStore(app));
            return new AuthViewModel(api, new AuthStateMachine(true, kakao, api));
        });
        ((UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click());
            awaitDisplayedText("R2a 구성원 편집");
            onView(withText("R2a 구성원 편집")).perform(click());
            onView(withId(R.id.trip_detail_edit)).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot(scenario, "01-admin-detail-portrait-light.png");
            TripViewModel model = tripModel(scenario);
            CountDownLatch membersReady = observeMembers(model);
            onView(withId(R.id.trip_detail_members)).perform(scrollTo(), click());
            assertTrue("member content event timed out", membersReady.await(20, TimeUnit.SECONDS));
            screenshot(scenario, "02-two-member-management-largefont.png");
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.trip_members_list);
                assertNotNull("member list requires a LayoutManager", list.getLayoutManager());
                assertEquals("two loaded members must remain two UI rows", 2, list.getAdapter().getItemCount());
                assertTrue("at least one loaded member row must be attached and usable", list.getChildCount() > 0);
            });
            scenario.recreate();
            onView(withId(R.id.trip_members_list)).check(matches(isDisplayed()));
            screenshot(scenario, "02b-two-member-management-largefont-portrait-recreated.png");
            onView(withId(R.id.trip_members_list)).check(matches(isDisplayed()));
            androidx.test.espresso.Espresso.pressBack();
            onView(withId(R.id.trip_detail_delete)).perform(scrollTo(), click());
            awaitDisplayedText(context.getString(R.string.trip_confirm_delete_title));
            screenshot(scenario, "03-delete-confirmation-no-capability.png");
            onView(withText(R.string.trip_confirm_cancel)).perform(click());
            scenario.recreate();
            onView(withId(R.id.trip_detail_edit)).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot(scenario, "04-admin-detail-portrait-recreated.png");
            scenario.onActivity(activity -> ((UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES));
            onView(withId(R.id.trip_detail_edit)).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot(scenario, "05-admin-detail-dark.png");
        }
    }

    private void showMemberUiAndClearPendingOnLogout(Context context, String credential, String tripId, String token) throws Exception {
        new SecureRefreshStore(context).clear();
        MutableKakao kakao = new MutableKakao(credential);
        AuthViewModel.installDebugFixture(app -> {
            AuthApiClient api = new AuthApiClient(URI.create("http://127.0.0.1:8080"), new SecureRefreshStore(app));
            return new AuthViewModel(api, new AuthStateMachine(true, kakao, api));
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click());
            awaitDisplayedText("R2a 구성원 편집");
            onView(withText("R2a 구성원 편집")).perform(click());
            onView(withId(R.id.trip_detail_leave)).perform(scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.trip_detail_delete).getVisibility());
                MainFragment main = (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                main.receiveInvitation("stog://trip-invitations/" + token);
            });
            onView(withId(R.id.trip_join_submit)).check(matches(isDisplayed()));
            TripViewModel model = tripModel(scenario);
            assertTrue(model.hasPendingJoin());
            onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click());
            awaitDisplayedText(context.getString(R.string.auth_signed_out_title));
            assertFalse(model.hasPendingJoin());
            scenario.recreate();
            awaitDisplayedText(context.getString(R.string.auth_signed_out_title));
            assertFalse(tripModel(scenario).hasPendingJoin());
        }
    }

    private static void assertMalformedInvitationSurface(URI backend, String bearer) throws Exception {
        String request = id();
        String[] invalid400 = {
            "{\"member_label\":\"검증\",\"request_id\":\"" + request + "\"}",
            "{\"invitation_token\":null,\"member_label\":\"검증\",\"request_id\":\"" + request + "\"}",
            "{\"invitation_token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"invitation_token\":\"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\",\"member_label\":\"검증\",\"request_id\":\"" + request + "\"}",
            "{\"invitation_token\":7,\"member_label\":\"검증\",\"request_id\":\"" + request + "\"}",
            "{\"invitation_token\":{},\"member_label\":\"검증\",\"request_id\":\"" + request + "\"}"
        };
        for (String body : invalid400) assertHttpError(backend, bearer, body, 400, "INVALID_REQUEST");
        String[] unusable404 = {
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        };
        for (String token : unusable404) assertHttpError(backend, bearer,
            "{\"invitation_token\":\"" + token + "\",\"member_label\":\"검증\",\"request_id\":\"" + id() + "\"}",
            404, "INVITATION_NOT_FOUND_OR_NOT_USABLE");
    }

    private static void assertHttpError(URI backend, String bearer, String body, int status, String code) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(backend + "/api/trip-invitations/join").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
        connection.setRequestProperty("Authorization", "Bearer " + bearer); connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        assertEquals(status, connection.getResponseCode());
        try (java.io.InputStream input = connection.getErrorStream()) {
            String response = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(code, new org.json.JSONObject(response).getJSONObject("error").getString("code"));
        } finally { connection.disconnect(); }
    }

    private static AuthApiClient.Trip create(AuthApiClient api, String name, LocalDate start) throws Exception {
        AuthApiClient.TripResult result = awaitTrip(callback -> api.createTrip(new AuthApiClient.TripCreateRequest(name, "서울", start.toString(), start.plusDays(2).toString(), id()), callback));
        assertEquals(AuthApiClient.TripOutcome.SUCCESS, result.outcome()); return result.trip();
    }
    private static AuthApiClient.Trip update(AuthApiClient api, AuthApiClient.Trip trip, String name) throws Exception {
        AuthApiClient.CollaborationResult result = collaboration(callback -> api.updateTrip(trip.tripId(), new AuthApiClient.TripUpdateRequest(name, trip.regionLabel(), trip.startDate(), trip.endDate(), trip.planVersion(), id()), callback));
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, result.outcome()); return result.trip();
    }
    private static String issue(AuthApiClient api, String tripId) throws Exception {
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        String token = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        AuthApiClient.CollaborationResult result = collaboration(callback -> api.issueInvitation(tripId, token, id(), callback));
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, result.outcome()); assertNotNull(result.inviteUri()); return token;
    }
    private static AuthApiClient.Trip detail(AuthApiClient api, String tripId) throws Exception { AuthApiClient.TripResult result=awaitTrip(callback -> api.tripDetail(tripId, callback)); assertEquals(AuthApiClient.TripOutcome.SUCCESS,result.outcome()); return result.trip(); }
    private static void exchange(AuthApiClient api, String token) throws Exception { CountDownLatch latch=new CountDownLatch(1);AtomicReference<Boolean> value=new AtomicReference<>();api.exchange(token,result->{value.set(result);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,value.get()); }
    private interface CollaborationCall { void call(AuthApiClient.CollaborationCallback callback); }
    private static AuthApiClient.CollaborationResult collaboration(CollaborationCall call) throws Exception { CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.CollaborationResult> result=new AtomicReference<>();call.call(value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));return result.get(); }
    private interface TripCall { void call(AuthApiClient.TripCallback callback); }
    private static AuthApiClient.TripResult awaitTrip(TripCall call) throws Exception { CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.TripResult> result=new AtomicReference<>();call.call(value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));return result.get(); }
    private static String id(){return UUID.randomUUID().toString();}
    private static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+key);return value;}
    private static TripViewModel tripModel(ActivityScenario<MainActivity> scenario) {
        AtomicReference<TripViewModel> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            MainFragment main = (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            value.set(new ViewModelProvider(main).get(TripViewModel.class));
        });
        return value.get();
    }
    private static CountDownLatch observeMembers(TripViewModel model) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.MembersState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            holder[0] = state -> {
                if (state.kind() == TripViewModel.MembersKind.CONTENT && state.page() != null) {
                    model.members().removeObserver(holder[0]); latch.countDown();
                }
            };
            model.members().observeForever(holder[0]);
        });
        return latch;
    }
    private static void awaitDisplayed(int id) { onView(withId(id)).check(matches(isDisplayed())); }
    private static void awaitDisplayedText(String text) { onView(withText(text)).check(matches(isDisplayed())); }
    private static void screenshot(ActivityScenario<MainActivity> scenario,String name)throws Exception{CountDownLatch frame=new CountDownLatch(1);scenario.onActivity(activity->{activity.getWindow().getDecorView().getViewTreeObserver().registerFrameCommitCallback(frame::countDown);activity.getWindow().getDecorView().invalidate();});assertTrue(frame.await(10,TimeUnit.SECONDS));Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/STOG-R2a-QA");android.net.Uri uri=InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);try(OutputStream output=InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().openOutputStream(uri)){assertNotNull(output);assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}}
    private static final class MutableKakao implements AuthStateMachine.KakaoGateway { private final String token; MutableKakao(String token){this.token=token;} public void login(AuthStateMachine.LoginCallback callback){callback.succeeded(token);} public void cleanup(AuthStateMachine.CompletionCallback callback){callback.completed(true);} }
}
