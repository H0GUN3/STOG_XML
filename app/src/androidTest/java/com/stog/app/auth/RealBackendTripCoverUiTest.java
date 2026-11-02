package com.stog.app.auth;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.MainActivity;
import com.stog.app.MainFragment;
import com.stog.app.R;
import com.stog.app.trip.TripViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Native document picker -> real Spring -> isolated PostgreSQL/private-files R2b acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RealBackendTripCoverUiTest {
    private static final URI BACKEND = URI.create("http://127.0.0.1:8080");
    private AuthApiClient owner;
    private AuthApiClient member;
    private final List<android.net.Uri> generatedDocuments = new ArrayList<>();
    private final List<File> generatedFiles = new ArrayList<>();

    @After public void cleanup() {
        AuthViewModel.clearDebugFixture();
        if (owner != null) owner.close();
        if (member != null) member.close();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        generatedDocuments.forEach(uri -> context.getContentResolver().delete(uri, null, null));
        generatedFiles.forEach(File::delete);
    }

    @Test public void nativePickerSanitizesReplaysSharesRevokesRemovesAndDeletesPrivately() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String ownerCredential = required(arguments, "ownerProviderToken");
        String ownerUiCredential = required(arguments, "ownerUiProviderToken");
        String memberCredential = required(arguments, "memberProviderToken");
        int controlPort = Integer.parseInt(required(arguments, "controlPort"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        owner = new AuthApiClient(BACKEND, new SecureRefreshStore(context));
        member = new AuthApiClient(BACKEND, new SecureRefreshStore(context));
        exchange(owner, ownerCredential); exchange(member, memberCredential);

        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(4);
        AuthApiClient.Trip trip = create(owner, "R2b 실제 대표 이미지", start);
        String token = issue(owner, trip.tripId());
        assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS,
            collaboration(callback -> member.joinInvitation(token, "두번째 구성원", id(), callback)).outcome());
        new SecureRefreshStore(context).clear();
        generatedDocuments.add(generatedPng(context, "stog-r2b-native-green.png", 0xff1f8f55));

        AtomicReference<AuthViewModel> auth = new AtomicReference<>();
        AuthViewModel.installDebugFixture(app -> {
            AuthApiClient api = new AuthApiClient(BACKEND, new SecureRefreshStore(app));
            AuthViewModel created = new AuthViewModel(api,
                new AuthStateMachine(true, new ImmediateKakao(ownerUiCredential), api));
            auth.set(created); return created;
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAuth(auth, AuthStateMachine.Kind.SIGNED_OUT, null);
            awaitAuth(auth, AuthStateMachine.Kind.SIGNED_IN,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            TripViewModel model = tripModel(scenario); awaitList(model);
            onView(withText("R2b 실제 대표 이미지")).perform(click()); awaitDetail(model);

            onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click());
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            onView(withId(R.id.trip_cover_status)).check(matches(withText(R.string.trip_cover_permission)));

            awaitCoverKindAfterAction(model, TripViewModel.CoverKind.PREVIEW,
                () -> selectNativeDocument("stog-r2b-native-green.png",
                    () -> onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click())));
            assertImageColor(scenario, 0xff1f8f55, 45);
            screenshot(scenario, "01-native-picker-preview-portrait.png");

            String initialCoverId;
            try (RemoteImageProbe remote = RemoteImageProbe.install(scenario, model, 0xff1f8f55, 55)) {
                awaitCoverKindAfterAction(model, TripViewModel.CoverKind.CONTENT,
                    () -> onView(withId(R.id.trip_cover_upload)).perform(scrollTo(), click()));
                initialCoverId = remote.awaitCoverId();
            }
            screenshot(scenario, "02-real-backend-sanitized-green.png");

            AuthApiClient.Trip ownerTrip = detail(owner, trip.tripId());
            assertEquals(initialCoverId, ownerTrip.cover().coverId());
            AuthApiClient.Trip memberTrip = detail(member, trip.tripId());
            assertEquals(ownerTrip.cover().coverId(), memberTrip.cover().coverId());
            assertImageResultColor(awaitImage(member, memberTrip.cover(), false), 0xff1f8f55, 55);
            assertImageResultColor(awaitImage(member, memberTrip.cover(), true), 0xff1f8f55, 60);

            generatedDocuments.add(generatedPng(context, "stog-r2b-native-blue.png", 0xff2456d8));
            awaitCoverKindAfterAction(model, TripViewModel.CoverKind.PREVIEW,
                () -> selectNativeDocument("stog-r2b-native-blue.png",
                    () -> onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click())));
            control(controlPort, "/drop-next-cover-response");
            awaitCoverKindAfterAction(model, TripViewModel.CoverKind.ERROR, () -> scenario.onActivity(activity -> {
                android.view.View upload = activity.findViewById(R.id.trip_cover_upload);
                assertTrue("replacement upload control must be enabled", upload.isEnabled());
                assertTrue("replacement upload control did not dispatch", upload.performClick());
            }));
            String replacementCoverId;
            try (RemoteImageProbe remote = RemoteImageProbe.install(scenario, model, 0xff2456d8, 55)) {
                awaitCoverKindAfterAction(model, TripViewModel.CoverKind.CONTENT,
                    () -> onView(withId(R.id.trip_cover_upload)).perform(scrollTo(), click()));
                replacementCoverId = remote.awaitCoverId();
            }
            assertFalse(initialCoverId.equals(replacementCoverId));
            screenshot(scenario, "03-dropped-replacement-replayed-blue.png");
            AuthApiClient.Trip memberReplacement = detail(member, trip.tripId());
            assertEquals(replacementCoverId, memberReplacement.cover().coverId());
            assertImageResultColor(awaitImage(member, memberReplacement.cover(), false), 0xff2456d8, 55);

            AuthApiClient.MemberPage members = collaboration(callback -> owner.members(trip.tripId(), null, callback)).members();
            AuthApiClient.Member other = members.items().stream().filter(value -> !value.self()).findFirst().orElseThrow();
            assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback ->
                owner.removeMember(trip.tripId(), other.memberUserId(), members.planVersion(), id(), callback)).outcome());
            assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND,
                awaitTrip(callback -> member.tripDetail(trip.tripId(), callback)).outcome());
            assertImageDenied(controlPort, member.accessToken(), memberReplacement.cover().filePath());
            assertImageDenied(controlPort, member.accessToken(), memberReplacement.cover().thumbnailPath());
            AuthApiClient.Trip afterRemoval = detail(owner, trip.tripId());
            awaitDetailVersionAfterAction(model, afterRemoval.planVersion(), () -> model.loadDetail());

            onView(withId(R.id.trip_cover_remove)).perform(scrollTo(), click());
            onView(withText(R.string.trip_confirm_cover_remove_title)).check(matches(isDisplayed()));
            screenshot(scenario, "04-remove-confirmation-cancel.png");
            onView(withText(R.string.trip_confirm_cancel)).perform(click());
            assertEquals(replacementCoverId, detail(owner, trip.tripId()).cover().coverId());
            onView(withId(R.id.trip_cover_remove)).perform(scrollTo(), click());
            awaitCoverKindAfterAction(model, TripViewModel.CoverKind.MISSING,
                () -> onView(withText(R.string.trip_confirm_continue)).perform(click()));
            awaitDetailWithoutCover(model);
            screenshot(scenario, "05-native-remove-complete.png");

            AuthApiClient.Trip noCover = detail(owner, trip.tripId()); String noCoverRequest = id();
            RawRemove unchanged = rawRemove(owner.accessToken(), trip.tripId(), noCover.planVersion(), noCoverRequest);
            RawRemove unchangedReplay = rawRemove(owner.accessToken(), trip.tripId(), noCover.planVersion(), noCoverRequest);
            assertEquals(200, unchanged.status()); assertEquals("UNCHANGED", unchanged.result());
            assertEquals(noCover.planVersion(), unchanged.planVersion()); assertEquals(unchanged, unchangedReplay);

            assertInvalidUploadsLeaveNoPointer(owner, context, start);

            String sharedRequest = id(); File red = generatedPrivatePng(context, "r2b-red.png", 0xffad3b3b, 64, 96); generatedFiles.add(red);
            AuthApiClient.Trip beforeShared = detail(owner, trip.tripId());
            AuthApiClient.CoverResult sharedSet = awaitCover(callback -> owner.setTripCover(trip.tripId(),
                new AuthApiClient.CoverUpload(red, "image/png", beforeShared.planVersion(), sharedRequest), callback));
            assertEquals(AuthApiClient.CoverOutcome.SUCCESS, sharedSet.outcome());
            RawRemove sharedRemove = rawRemove(owner.accessToken(), trip.tripId(), sharedSet.planVersion(), sharedRequest);
            assertEquals(200, sharedRemove.status()); assertEquals("REMOVED", sharedRemove.result());

            File retained = generatedPrivatePng(context, "r2b-retained.png", 0xffad3b3b, 64, 96); generatedFiles.add(retained);
            AuthApiClient.Trip beforeRetained = detail(owner, trip.tripId());
            AuthApiClient.CoverResult retainedSet = awaitCover(callback -> owner.setTripCover(trip.tripId(),
                new AuthApiClient.CoverUpload(retained, "image/png", beforeRetained.planVersion(), id()), callback));
            AuthApiClient.Trip beforeDelete = detail(owner, trip.tripId()); String deleteRequest = id();
            assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, collaboration(callback -> owner.deleteTrip(trip.tripId(), beforeDelete.planVersion(), deleteRequest, callback)).outcome());
            assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND, awaitTrip(callback -> owner.tripDetail(trip.tripId(), callback)).outcome());
            assertImageDenied(controlPort, owner.accessToken(), retainedSet.cover().filePath());
            assertImageDenied(controlPort, owner.accessToken(), retainedSet.cover().thumbnailPath());

            awaitAuth(auth, AuthStateMachine.Kind.SIGNED_OUT,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            scenario.onActivity(activity -> assertEquals(android.view.View.GONE, activity.findViewById(R.id.trip_product_container).getVisibility()));
            screenshot(scenario, "06-logout-clears-private-cover.png");
        }
    }

    private void assertInvalidUploadsLeaveNoPointer(AuthApiClient api, Context context, LocalDate start) throws Exception {
        AuthApiClient.Trip disposable = create(api, "R2b invalid inputs", start.plusDays(10));
        byte[] truncated = {(byte)0xff,(byte)0xd8,1,2};
        assertEquals(400, multipartStatus(api.accessToken(), disposable.tripId(), disposable.planVersion(), id(), "image/jpeg", truncated));
        File png = generatedPrivatePng(context, "wrong-mime.png", 0xff00a040, 8, 8); generatedFiles.add(png);
        assertEquals(400, multipartStatus(api.accessToken(), disposable.tripId(), disposable.planVersion(), id(), "image/jpeg", java.nio.file.Files.readAllBytes(png.toPath())));
        assertEquals(413, multipartStatus(api.accessToken(), disposable.tripId(), disposable.planVersion(), id(), "image/jpeg", new byte[8 * 1024 * 1024 + 1]));
        File wide = generatedPrivatePng(context, "wide.png", 0xff00a040, 8193, 1); generatedFiles.add(wide);
        assertEquals(413, multipartStatus(api.accessToken(), disposable.tripId(), disposable.planVersion(), id(), "image/png", java.nio.file.Files.readAllBytes(wide.toPath())));
        assertNull(detail(api, disposable.tripId()).cover());
    }

    private static void assertImageDenied(int controlPort,String bearer,String path)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+controlPort+"/probe-denied-cover").openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setRequestProperty("Content-Type","application/json");byte[] body=new org.json.JSONObject().put("bearer",bearer).put("path",path).toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(OutputStream output=connection.getOutputStream()){output.write(body);}assertEquals(200,connection.getResponseCode());try(InputStream input=connection.getInputStream()){org.json.JSONObject result=new org.json.JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));assertEquals(404,result.getInt("status"));assertEquals("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE",result.getString("code"));}finally{connection.disconnect();}}
    private static RawRemove rawRemove(String bearer,String tripId,long version,String requestId)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(BACKEND+"/api/trips/"+tripId+"/cover").openConnection();connection.setRequestMethod("DELETE");connection.setDoOutput(true);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setRequestProperty("Authorization","Bearer "+bearer);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] body=("{\"expected_plan_version\":"+version+",\"request_id\":\""+requestId+"\"}").getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(OutputStream output=connection.getOutputStream()){output.write(body);}int status=connection.getResponseCode();try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream()){String text=input==null?"":new String(input.readAllBytes(),StandardCharsets.UTF_8);org.json.JSONObject json=new org.json.JSONObject(text);return new RawRemove(status,json.optString("result",null),json.optLong("plan_version",-1));}finally{connection.disconnect();}}
    private static void control(int port,String path)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();connection.setRequestMethod("POST");connection.setFixedLengthStreamingMode(0);connection.setDoOutput(true);try(OutputStream output=connection.getOutputStream()){output.flush();}assertEquals(204,connection.getResponseCode());connection.disconnect();}
    private record RawRemove(int status,String result,long planVersion){}

    private static int multipartStatus(String bearer, String tripId, long version, String requestId, String mediaType, byte[] image) throws Exception {
        String boundary = "verify-" + id(); byte[] command = ("{\"expected_plan_version\":" + version + ",\"request_id\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cover\"\r\nContent-Type: "+mediaType+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); body.write(image); body.write(("\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"command\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); body.write(command); body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpURLConnection connection=(HttpURLConnection)new URL(BACKEND+"/api/trips/"+tripId+"/cover").openConnection(); connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(10000); connection.setReadTimeout(20000); connection.setRequestProperty("Authorization","Bearer "+bearer); connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary); connection.setFixedLengthStreamingMode(body.size()); try(OutputStream output=connection.getOutputStream()){body.writeTo(output);} int status=connection.getResponseCode(); try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream()){if(input!=null)input.readAllBytes();} finally{connection.disconnect();} return status;
    }

    private static void selectNativeDocument(String fileName, Runnable action) throws Exception {
        android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(); CountDownLatch opened=new CountDownLatch(1); automation.setOnAccessibilityEventListener(event->{if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getPackageName()!=null&&event.getPackageName().toString().contains("documentsui"))opened.countDown();});
        try { action.run(); assertTrue("system document picker did not open",opened.await(20,TimeUnit.SECONDS)); AccessibilityNodeInfo root=automation.getRootInActiveWindow(); assertNotNull(root); List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(fileName); assertFalse("generated document not visible in native picker",nodes.isEmpty()); assertTrue(nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
        finally { automation.setOnAccessibilityEventListener(null); }
    }

    private static android.net.Uri generatedPng(Context context,String name,int color)throws Exception{ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"image/png");values.put(MediaStore.Downloads.RELATIVE_PATH,"Download");android.net.Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(uri);Bitmap bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);try(OutputStream output=context.getContentResolver().openOutputStream(uri)){assertNotNull(output);assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}return uri;}
    private static File generatedPrivatePng(Context context,String name,int color,int width,int height)throws Exception{File file=new File(context.getCacheDir(),name);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}return file;}
    private static void assertImageColor(ActivityScenario<MainActivity> scenario,int expected,int tolerance){scenario.onActivity(activity->{BitmapDrawable drawable=(BitmapDrawable)((android.widget.ImageView)activity.findViewById(R.id.trip_cover_image)).getDrawable();assertNotNull(drawable);assertColor(expected,drawable.getBitmap().getPixel(drawable.getBitmap().getWidth()/2,drawable.getBitmap().getHeight()/2),tolerance);});}
    private static void assertImageResultColor(AuthApiClient.CoverImageResult result,int expected,int tolerance){assertEquals(AuthApiClient.CoverImageOutcome.CONTENT,result.outcome());Bitmap bitmap=result.bitmap();assertNotNull(bitmap);try{assertColor(expected,bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2),tolerance);}finally{bitmap.recycle();}}
    private static void assertColor(int expected,int actual,int tolerance){assertTrue(Math.abs(android.graphics.Color.red(expected)-android.graphics.Color.red(actual))<=tolerance);assertTrue(Math.abs(android.graphics.Color.green(expected)-android.graphics.Color.green(actual))<=tolerance);assertTrue(Math.abs(android.graphics.Color.blue(expected)-android.graphics.Color.blue(actual))<=tolerance);}
    private interface ThrowingAction { void run() throws Exception; }
    private static void awaitCoverKindAfterAction(TripViewModel model,TripViewModel.CoverKind kind,ThrowingAction action)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<TripViewModel.CoverState>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==kind)latch.countDown();};model.cover().observeForever(holder[0]);});try{action.run();assertTrue("cover state timed out: "+kind+"; final="+model.cover().getValue().kind(),latch.await(30,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.cover().removeObserver(holder[0]));}}
    private static final class RemoteImageProbe implements AutoCloseable {private final ActivityScenario<MainActivity> scenario;private final TripViewModel model;private final int expectedColor;private final int tolerance;private final CountDownLatch ready=new CountDownLatch(1);private final AtomicReference<String> expectedCoverId=new AtomicReference<>();private Observer<TripViewModel.CoverState> coverObserver;private android.widget.ImageView image;private android.view.ViewTreeObserver.OnPreDrawListener drawListener;private RemoteImageProbe(ActivityScenario<MainActivity>s,TripViewModel m,int c,int t){scenario=s;model=m;expectedColor=c;tolerance=t;}static RemoteImageProbe install(ActivityScenario<MainActivity>s,TripViewModel m,int c,int t){RemoteImageProbe probe=new RemoteImageProbe(s,m,c,t);probe.install();return probe;}private void install(){scenario.onActivity(activity->{image=activity.findViewById(R.id.trip_cover_image);drawListener=()->{String expected=expectedCoverId.get();TripViewModel.DetailState detail=model.detail().getValue();if(expected!=null&&detail!=null&&detail.trip()!=null&&detail.trip().cover()!=null&&expected.equals(detail.trip().cover().coverId())&&image.getDrawable() instanceof BitmapDrawable drawable){Bitmap bitmap=drawable.getBitmap();int pixel=bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2);if(colorMatches(expectedColor,pixel,tolerance))ready.countDown();}return true;};image.getViewTreeObserver().addOnPreDrawListener(drawListener);});InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{coverObserver=state->{if(state.kind()==TripViewModel.CoverKind.CONTENT&&state.cover()!=null)expectedCoverId.set(state.cover().coverId());};model.cover().observeForever(coverObserver);});}String awaitCoverId()throws Exception{assertTrue("exact remote cover identity/pixels did not render",ready.await(30,TimeUnit.SECONDS));return expectedCoverId.get();}public void close(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(coverObserver!=null)model.cover().removeObserver(coverObserver);if(image!=null&&drawListener!=null&&image.getViewTreeObserver().isAlive())image.getViewTreeObserver().removeOnPreDrawListener(drawListener);});}}
    private static boolean colorMatches(int expected,int actual,int tolerance){return Math.abs(android.graphics.Color.red(expected)-android.graphics.Color.red(actual))<=tolerance&&Math.abs(android.graphics.Color.green(expected)-android.graphics.Color.green(actual))<=tolerance&&Math.abs(android.graphics.Color.blue(expected)-android.graphics.Color.blue(actual))<=tolerance;}
    private static void awaitList(TripViewModel model)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<TripViewModel.ListState>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==TripViewModel.ListKind.CONTENT)latch.countDown();};model.list().observeForever(holder[0]);if(model.list().getValue()!=null&&model.list().getValue().kind()==TripViewModel.ListKind.CONTENT)latch.countDown();});try{assertTrue("trip list timed out",latch.await(20,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.list().removeObserver(holder[0]));}}
    private static void awaitDetail(TripViewModel model)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==TripViewModel.DetailKind.CONTENT&&state.basket()!=null)latch.countDown();};model.detail().observeForever(holder[0]);if(model.detail().getValue()!=null&&model.detail().getValue().kind()==TripViewModel.DetailKind.CONTENT&&model.detail().getValue().basket()!=null)latch.countDown();});try{assertTrue("trip detail timed out",latch.await(20,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.detail().removeObserver(holder[0]));}}
    private static void awaitDetailVersionAfterAction(TripViewModel model,long version,Runnable action)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==TripViewModel.DetailKind.CONTENT&&state.trip()!=null&&state.trip().planVersion()==version)latch.countDown();};model.detail().observeForever(holder[0]);});try{InstrumentationRegistry.getInstrumentation().runOnMainSync(action);assertTrue("detail version reload timed out: "+version,latch.await(20,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.detail().removeObserver(holder[0]));}}
    private static void awaitDetailWithoutCover(TripViewModel model)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==TripViewModel.DetailKind.CONTENT&&state.trip()!=null&&state.trip().cover()==null)latch.countDown();};model.detail().observeForever(holder[0]);if(model.detail().getValue()!=null&&model.detail().getValue().kind()==TripViewModel.DetailKind.CONTENT&&model.detail().getValue().trip()!=null&&model.detail().getValue().trip().cover()==null)latch.countDown();});try{assertTrue("detail without cover timed out",latch.await(20,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.detail().removeObserver(holder[0]));}}
    private static void awaitAuth(AtomicReference<AuthViewModel> auth,AuthStateMachine.Kind kind,Runnable action)throws Exception{CountDownLatch latch=new CountDownLatch(1);@SuppressWarnings("unchecked") Observer<AuthStateMachine.State>[] holder=new Observer[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=state->{if(state.kind()==kind)latch.countDown();};auth.get().state().observeForever(holder[0]);if(auth.get().state().getValue()!=null&&auth.get().state().getValue().kind()==kind)latch.countDown();});try{if(action!=null)action.run();assertTrue("auth state timed out: "+kind,latch.await(20,TimeUnit.SECONDS));}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->auth.get().state().removeObserver(holder[0]));}}
    private static AuthApiClient.Trip create(AuthApiClient api,String name,LocalDate start)throws Exception{AuthApiClient.TripResult result=awaitTrip(callback->api.createTrip(new AuthApiClient.TripCreateRequest(name,"서울",start.toString(),start.plusDays(2).toString(),id()),callback));assertEquals(AuthApiClient.TripOutcome.SUCCESS,result.outcome());return result.trip();}
    private static String issue(AuthApiClient api,String tripId)throws Exception{byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=android.util.Base64.encodeToString(bytes,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);AuthApiClient.CollaborationResult result=collaboration(callback->api.issueInvitation(tripId,token,id(),callback));assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS,result.outcome());return token;}
    private static AuthApiClient.Trip detail(AuthApiClient api,String tripId)throws Exception{AuthApiClient.TripResult result=awaitTrip(callback->api.tripDetail(tripId,callback));assertEquals(AuthApiClient.TripOutcome.SUCCESS,result.outcome());return result.trip();}
    private static void exchange(AuthApiClient api,String token)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<Boolean> result=new AtomicReference<>();api.exchange(token,value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,result.get());}
    private static AuthApiClient.CoverImageResult awaitImage(AuthApiClient api,AuthApiClient.Cover cover,boolean thumbnail)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.CoverImageResult> result=new AtomicReference<>();api.coverImage(cover,thumbnail,value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));return result.get();}
    private interface CoverCall{void call(AuthApiClient.CoverCallback callback);}private static AuthApiClient.CoverResult awaitCover(CoverCall call)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.CoverResult> result=new AtomicReference<>();call.call(value->{result.set(value);latch.countDown();});assertTrue(latch.await(30,TimeUnit.SECONDS));return result.get();}
    private interface CollaborationCall{void call(AuthApiClient.CollaborationCallback callback);}private static AuthApiClient.CollaborationResult collaboration(CollaborationCall call)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.CollaborationResult> result=new AtomicReference<>();call.call(value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));return result.get();}
    private interface TripCall{void call(AuthApiClient.TripCallback callback);}private static AuthApiClient.TripResult awaitTrip(TripCall call)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<AuthApiClient.TripResult> result=new AtomicReference<>();call.call(value->{result.set(value);latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));return result.get();}
    private static TripViewModel tripModel(ActivityScenario<MainActivity> scenario){AtomicReference<TripViewModel> value=new AtomicReference<>();scenario.onActivity(activity->{MainFragment main=(MainFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);value.set(new ViewModelProvider(main).get(TripViewModel.class));});return value.get();}
    private static void screenshot(ActivityScenario<MainActivity> scenario,String name)throws Exception{CountDownLatch frame=new CountDownLatch(1);scenario.onActivity(activity->{activity.getWindow().getDecorView().getViewTreeObserver().registerFrameCommitCallback(frame::countDown);activity.getWindow().getDecorView().invalidate();});assertTrue(frame.await(20,TimeUnit.SECONDS));Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/STOG-R2b-Real-E2E");android.net.Uri uri=InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);try(OutputStream output=InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().openOutputStream(uri)){assertNotNull(output);assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}}
    private static String id(){return UUID.randomUUID().toString();}private static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+key);return value;}
    private record ImmediateKakao(String token) implements AuthStateMachine.KakaoGateway{public void login(AuthStateMachine.LoginCallback callback){callback.succeeded(token);}public void cleanup(AuthStateMachine.CompletionCallback callback){callback.completed(true);}}
}
