package com.stog.app.auth;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralSwipeAction;
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
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.Press.FINGER;
import static androidx.test.espresso.action.Swipe.FAST;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Generated catalog Android UI -> actual Spring backend -> isolated PostgreSQL V1-V6 acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RealBackendPlaceFlowUiTest {
    private AuthApiClient api;
    private final AtomicReference<AuthViewModel> auth = new AtomicReference<>();
    private final AtomicReference<AuthStateMachine> machine = new AtomicReference<>();
    private final MutableKakao kakao = new MutableKakao();

    @After public void cleanup() { AuthViewModel.clearDebugFixture(); if (api != null) api.close(); }

    @Test public void selectedTripSearchDetailLostSaveReplayBackRecreationAndLogout() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        kakao.token.set(required(args,"providerToken")); String expectedPlaceId = required(args,"placeId");
        AuthViewModel.installDebugFixture(context -> { api = new AuthApiClient(URI.create("http://127.0.0.1:8080"),new SecureRefreshStore(context)); AuthStateMachine state = new AuthStateMachine(true,kakao,api); AuthViewModel value = new AuthViewModel(api,state); auth.set(value); machine.set(state); return value; });
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            awaitAuth(scenario,AuthStateMachine.Kind.SIGNED_OUT,null); awaitAuth(scenario,AuthStateMachine.Kind.SIGNED_IN,()->onView(withId(R.id.auth_primary_action)).perform(scrollTo(),click()));
            TripViewModel model=tripModel(scenario); awaitList(model,TripViewModel.ListKind.CONTENT); onView(withText("R3 Integration Trip")).perform(click()); awaitDetail(model);
            onView(withId(R.id.trip_add_place)).perform(scrollTo(),click());
            scenario.onActivity(activity->{activity.findViewById(R.id.place_query_input).requestFocus();});
            onView(withId(R.id.place_query_input)).perform(replaceText("  제주\u00a0카페  ")); onView(withId(R.id.place_region_input)).perform(replaceText(" 제주 ")); onView(withId(R.id.place_category_input)).perform(replaceText(" 카페 ")); screenshot(scenario,"01-search-keyboard-largefont.png"); closeSoftKeyboard();
            CountDownLatch first=observePlaceCount(model,20); onView(withId(R.id.place_search_submit)).perform(click()); assertTrue("real first catalog page timed out",first.await(20,TimeUnit.SECONDS)); assertEquals(20,model.placeSearch().getValue().places().size()); screenshot(scenario,"02-real-search-first-page.png");
            CountDownLatch continued=observePlaceCount(model,21); ViewAction[] gestures=new ViewAction[40]; Arrays.fill(gestures,shortSwipeUp()); onView(withId(R.id.place_search_results)).perform(gestures); assertTrue("native outer-scroll did not request the bounded continuation",continued.await(20,TimeUnit.SECONDS)); screenshot(scenario,"03-real-search-second-page.png");
            CountDownLatch place=observePlace(model,TripViewModel.PlaceDetailKind.CONTENT); ViewAction[] reverseGestures=new ViewAction[40]; Arrays.fill(reverseGestures,shortSwipeDown()); onView(withId(R.id.place_search_results)).perform(reverseGestures); onView(withText("제주 카페 00")).check(matches(isDisplayingAtLeast(90))).perform(click()); assertTrue(place.await(20,TimeUnit.SECONDS)); assertEquals(expectedPlaceId,model.placeDetail().getValue().place().placeId()); onView(withText(R.string.place_coordinates_missing)).check(matches(isDisplayed())); screenshot(scenario,"04-real-place-detail.png");
            recreateAndAwait(scenario); model=tripModel(scenario); assertEquals(expectedPlaceId,model.placeDetail().getValue().place().placeId());
            CountDownLatch ambiguous=observeSaveAfter(model,TripViewModel.PlaceSaveKind.RETRYABLE_ERROR); onView(withId(R.id.place_save_action)).perform(scrollTo(),click()); assertTrue("committed basket response was not observed as ambiguous",ambiguous.await(20,TimeUnit.SECONDS));
            CountDownLatch created=observeSaveAfter(model,TripViewModel.PlaceSaveKind.CREATED),basket=observeBasketCount(model,1); onView(withId(R.id.place_save_action)).perform(scrollTo(),click()); assertTrue("same save intent did not replay",created.await(20,TimeUnit.SECONDS)); assertTrue("basket did not reload the saved common Place",basket.await(20,TimeUnit.SECONDS)); screenshot(scenario,"05-real-save-replayed.png");
            pressBack(); onView(withId(R.id.place_query_input)).check(matches(withText("  제주\u00a0카페  "))); assertEquals(21,model.placeSearch().getValue().places().size()); screenshot(scenario,"06-search-restored-after-back.png");
            awaitAuth(scenario,AuthStateMachine.Kind.SIGNED_OUT,()->onView(withId(R.id.auth_primary_action)).perform(scrollTo(),click())); assertEquals(TripViewModel.PlaceSearchKind.AUTH_ENDED,model.placeSearch().getValue().kind()); assertFalse(scenarioState(scenario,R.id.trip_product_container)); screenshot(scenario,"07-logout-no-private-place.png");
        }
    }

    private static GeneralSwipeAction shortSwipeUp(){return boundedSwipe(true);}
    private static GeneralSwipeAction shortSwipeDown(){return boundedSwipe(false);}
    private static GeneralSwipeAction boundedSwipe(boolean up){return new GeneralSwipeAction(FAST,view->{float d=64f*view.getResources().getDisplayMetrics().density;return new float[]{view.getWidth()/2f,view.getHeight()/2f+(up?d:-d)};},view->{float d=64f*view.getResources().getDisplayMetrics().density;return new float[]{view.getWidth()/2f,view.getHeight()/2f+(up?-d:d)};},FINGER);}

    private static TripViewModel tripModel(ActivityScenario<MainActivity>s){AtomicReference<TripViewModel>v=new AtomicReference<>();s.onActivity(a->{MainFragment m=(MainFragment)a.getSupportFragmentManager().findFragmentById(R.id.fragment_container);v.set(new ViewModelProvider(m).get(TripViewModel.class));});return v.get();}
    private static boolean scenarioState(ActivityScenario<MainActivity>s,int id){AtomicBoolean visible=new AtomicBoolean();s.onActivity(a->visible.set(a.findViewById(id).getVisibility()==View.VISIBLE));return visible.get();}
    private static CountDownLatch observePlaceCount(TripViewModel m,int count){CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->m.placeSearch().observeForever(v->{if(v.kind()==TripViewModel.PlaceSearchKind.CONTENT&&v.places().size()==count)l.countDown();}));return l;}
    private static CountDownLatch observePlace(TripViewModel m,TripViewModel.PlaceDetailKind k){CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->m.placeDetail().observeForever(v->{if(v.kind()==k)l.countDown();}));return l;}
    private static CountDownLatch observeSaveAfter(TripViewModel m,TripViewModel.PlaceSaveKind k){CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AtomicBoolean saving=new AtomicBoolean();m.placeSave().observeForever(v->{if(v.kind()==TripViewModel.PlaceSaveKind.SAVING)saving.set(true);if(saving.get()&&v.kind()==k)l.countDown();});});return l;}
    private static CountDownLatch observeBasketCount(TripViewModel m,int count){CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->m.detail().observeForever(v->{if(v.kind()==TripViewModel.DetailKind.CONTENT&&v.basket()!=null&&v.basket().items().size()==count)l.countDown();}));return l;}
    private static void awaitList(TripViewModel m,TripViewModel.ListKind k)throws Exception{if(m.list().getValue()!=null&&m.list().getValue().kind()==k)return;CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->m.list().observeForever(v->{if(v.kind()==k)l.countDown();}));assertTrue(l.await(20,TimeUnit.SECONDS));}
    private static void awaitDetail(TripViewModel m)throws Exception{CountDownLatch l=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->m.detail().observeForever(v->{if(v.kind()==TripViewModel.DetailKind.CONTENT&&v.basket()!=null)l.countDown();}));if(m.detail().getValue()!=null&&m.detail().getValue().kind()==TripViewModel.DetailKind.CONTENT)return;assertTrue(l.await(20,TimeUnit.SECONDS));}
    private void awaitAuth(ActivityScenario<MainActivity>s,AuthStateMachine.Kind k,Runnable action)throws Exception{CountDownLatch l=new CountDownLatch(1);s.onActivity(a->auth.get().state().observeForever(v->{if(v.kind()==k)l.countDown();}));if(action!=null)action.run();assertTrue("auth timeout "+k,l.await(20,TimeUnit.SECONDS));}
    private static void recreateAndAwait(ActivityScenario<MainActivity>s)throws Exception{AtomicReference<MainActivity>old=new AtomicReference<>();CountDownLatch l=new CountDownLatch(1);android.app.Application app=(android.app.Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();android.app.Application.ActivityLifecycleCallbacks c=new android.app.Application.ActivityLifecycleCallbacks(){public void onActivityCreated(android.app.Activity a,Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityResumed(android.app.Activity a){if(a instanceof MainActivity&&a!=old.get())l.countDown();}public void onActivityPaused(android.app.Activity a){}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,Bundle b){}public void onActivityDestroyed(android.app.Activity a){}};app.registerActivityLifecycleCallbacks(c);try{s.onActivity(old::set);s.recreate();assertTrue(l.await(20,TimeUnit.SECONDS));}finally{app.unregisterActivityLifecycleCallbacks(c);}}
    private static void screenshot(ActivityScenario<MainActivity>s,String name)throws Exception{CountDownLatch l=new CountDownLatch(1);s.onActivity(a->{View root=a.getWindow().getDecorView();root.getViewTreeObserver().registerFrameCommitCallback(l::countDown);root.invalidate();});assertTrue(l.await(10,TimeUnit.SECONDS));Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,name);v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/STOG-R3a-Real-E2E");android.net.Uri u=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);try(OutputStream o=c.getContentResolver().openOutputStream(u)){assertTrue(o!=null&&b.compress(Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
    private static String required(Bundle b,String n){String v=b.getString(n);if(v==null||v.isBlank())throw new IllegalStateException("Missing "+n);return v;}
    private static final class MutableKakao implements AuthStateMachine.KakaoGateway{final AtomicReference<String>token=new AtomicReference<>();public void login(AuthStateMachine.LoginCallback c){c.succeeded(token.get());}public void cleanup(AuthStateMachine.CompletionCallback c){c.completed(true);}}
}
