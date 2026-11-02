package com.stog.app.auth;

import android.app.Activity;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.stog.app.MainActivity;
import com.stog.app.R;
import com.stog.app.trip.TripDetailFragment;
import com.stog.app.trip.TripViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class TripFlowUiTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String OTHER_TRIP = "a40e857d-b025-4f72-b420-2ee354a514ad";
    @After public void cleanup() { AuthViewModel.clearDebugFixture(); TripDetailFragment.clearDebugPicker(); }

    @Test public void createDetailRecreationSystemBackReturnsOneTripThenRootBackFinishes() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            AtomicReference<AuthViewModel> viewModel = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context));
                AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api)));
                viewModel.set(created); return created;
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1);
                scenario.onActivity(activity -> viewModel.get().state().observeForever(state -> {
                    if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown();
                }));
                assertTrue(fixture.exchangeRead.await(10, TimeUnit.SECONDS));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS));
                scenario.onActivity(activity -> activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container)
                    .getChildFragmentManager().executePendingTransactions());
                assertTrue(fixture.listRead.await(10, TimeUnit.SECONDS));
                onView(withId(R.id.trip_list_status)).check(matches(withText(R.string.trips_empty)));
                onView(withId(R.id.trip_create_action)).perform(click());
                onView(withId(R.id.trip_create_submit)).check(matches(withText(R.string.trip_create_submit)));
                scenario.onActivity(activity -> {
                    ((android.widget.EditText) activity.findViewById(R.id.trip_name_input)).setText("제주 여행");
                    ((android.widget.EditText) activity.findViewById(R.id.trip_region_input)).setText("제주");
                    ((android.widget.EditText) activity.findViewById(R.id.trip_start_input)).setText("2026-10-10");
                    ((android.widget.EditText) activity.findViewById(R.id.trip_end_input)).setText("2026-10-12");
                });
                AtomicReference<TripViewModel.ListState> lastListState = new AtomicReference<>();
                CountDownLatch createdList = observeCreatedListAfter(scenario, lastListState);
                onView(withId(R.id.trip_create_submit)).perform(scrollTo(), click());
                assertTrue(fixture.basketRead.await(10, TimeUnit.SECONDS));
                onView(withId(R.id.trip_detail_name)).check(matches(withText("제주 여행")));
                onView(withId(R.id.trip_basket_status)).check(matches(withText(R.string.trip_basket_empty)));
                recreateAndAwait(scenario);

                scenario.onActivity(activity -> {
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    androidx.fragment.app.FragmentManager child = main.getChildFragmentManager();
                    child.executePendingTransactions();
                    assertTrue("create success must leave exactly one detail-to-list entry",
                        child.getBackStackEntryCount() == 1);
                });
                androidx.test.espresso.Espresso.pressBack();
                assertTrue("created trip list refresh timed out after Back; last=" + lastListState.get(),
                    createdList.await(10, TimeUnit.SECONDS));
                onView(withId(R.id.trip_list)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                scenario.onActivity(activity -> assertEquals("created trip must appear exactly once after detail Back", 1,
                    ((RecyclerView) activity.findViewById(R.id.trip_list)).getAdapter().getItemCount()));
                scenario.onActivity(activity -> {
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    assertEquals("detail Back must consume the only child entry", 0,
                        main.getChildFragmentManager().getBackStackEntryCount());
                });

                screenshot(scenario);
                Application application = (Application) InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getApplicationContext();
                CountDownLatch rootDestroyed = new CountDownLatch(1);
                AtomicReference<MainActivity> rootActivity = new AtomicReference<>();
                Application.ActivityLifecycleCallbacks rootLifecycle = lifecycleCallbacks(activity -> {
                    if (activity == rootActivity.get()) rootDestroyed.countDown();
                });
                application.registerActivityLifecycleCallbacks(rootLifecycle);
                try {
                    scenario.onActivity(rootActivity::set);
                    androidx.test.espresso.Espresso.pressBackUnconditionally();
                    assertTrue("root list Back must retain normal Activity exit",
                        rootDestroyed.await(10, TimeUnit.SECONDS));
                } finally {
                    application.unregisterActivityLifecycleCallbacks(rootLifecycle);
                }
            }
        }
    }

    @Test public void nativeCoverPickerCancellationReturnsToThePrivateDetailSurface() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true;
            AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context));
                AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api)));
                auth.set(created); return created;
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1);
                scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS));
                assertTrue(fixture.listRead.await(10, TimeUnit.SECONDS));
                onView(withText("제주 여행")).perform(click());
                onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click());
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                scenario.onActivity(activity -> { });
                onView(withId(R.id.trip_cover_select)).perform(scrollTo()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                onView(withId(R.id.trip_cover_status)).check(matches(withText(R.string.trip_cover_permission)));
            }
        }
    }

    @Test public void delayedPickerPreparationAfterAuthEndDisposesPrivateInputWithoutRestoringCoverState() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true; AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api))); auth.set(created); return created; });
            android.net.Uri generated = generatedJpeg(); clearPrivateCover(); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1); CountDownLatch discarded = new CountDownLatch(1);
            TripDetailFragment.installDebugPicker(generated);
            TripDetailFragment.installDebugPreparationHook(new TripDetailFragment.DebugPreparationHook() {
                @Override public void beforePreparation() throws Exception { entered.countDown(); assertTrue("test did not release delayed preparation", release.await(10, TimeUnit.SECONDS)); }
                @Override public void discarded() { discarded.countDown(); }
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1); scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS)); assertTrue(fixture.listRead.await(10, TimeUnit.SECONDS)); onView(withText("제주 여행")).perform(click());
                TripViewModel model = tripModel(scenario); onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click()); assertTrue("preparation did not enter controlled delay", entered.await(10, TimeUnit.SECONDS));
                scenario.onActivity(activity -> model.authEnded()); release.countDown(); assertTrue("late prepared input was not disposed", discarded.await(10, TimeUnit.SECONDS));
                assertEquals(TripViewModel.CoverKind.AUTH_ENDED, model.cover().getValue().kind()); assertNull(model.selectedTripId()); assertNoPrivateCover();
            } finally { InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().delete(generated, null, null); }
        }
    }

    @Test public void delayedPickerPreparationAfterViewDestructionDisposesPrivateInput() throws Exception {
        assertDelayedPickerInvalidation((scenario, model) -> androidx.test.espresso.Espresso.pressBack(), null);
    }

    @Test public void delayedPickerPreparationAfterTripChangeDisposesPrivateInput() throws Exception {
        assertDelayedPickerInvalidation((scenario, model) -> scenario.onActivity(activity -> model.select(OTHER_TRIP)), OTHER_TRIP);
    }

    @Test public void authEndAfterRenderedCoverClearsPrivateBitmapImmediately() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true; fixture.withCover = true; AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api))); auth.set(created); return created; });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitSignedInAndOpenTrip(scenario, auth, fixture); awaitCoverDrawable(scenario);
                TripViewModel model = tripModel(scenario); scenario.onActivity(activity -> {
                    android.widget.ImageView image = activity.findViewById(R.id.trip_cover_image); assertNotNull(image.getDrawable()); model.authEnded(); assertNull("auth end must clear the rendered private cover", image.getDrawable());
                });
                screenshot(scenario, "r2b-auth-ended-cover-cleared.png");
            }
        }
    }

    @Test public void membershipDenialAfterRenderedCoverClearsPrivateBitmap() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true; fixture.withCover = true; AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api))); auth.set(created); return created; });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitSignedInAndOpenTrip(scenario, auth, fixture); awaitCoverDrawable(scenario); TripViewModel model = tripModel(scenario); CountDownLatch denied = new CountDownLatch(1);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.detail().observeForever(state -> { if (state.kind() == TripViewModel.DetailKind.ERROR) denied.countDown(); }));
                fixture.denyTripDetail = true; scenario.onActivity(activity -> model.loadDetail()); assertTrue("membership denial did not reach terminal detail state", denied.await(10, TimeUnit.SECONDS));
                scenario.onActivity(activity -> assertNull("membership denial must clear the rendered private cover", ((android.widget.ImageView) activity.findViewById(R.id.trip_cover_image)).getDrawable()));
                screenshot(scenario, "r2b-membership-denied-cover-cleared.png");
            }
        }
    }

    @Test public void generatedPickerPreviewUploadAndRemoveDialogRenderAcrossConfiguredSurface() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true;
            AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context));
                AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api)));
                auth.set(created); return created;
            });
            android.net.Uri generated = generatedJpeg(); String originalAutoRotation = shell("settings get system accelerometer_rotation").trim(); String originalUserRotation = shell("settings get system user_rotation").trim();
            ((android.app.UiModeManager) InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO);
            TripDetailFragment.installDebugPicker(generated);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1);
                scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS)); assertTrue(fixture.listRead.await(10, TimeUnit.SECONDS));
                onView(withText("제주 여행")).perform(click());
                TripViewModel model = tripModel(scenario); CountDownLatch preview = observeCover(model, TripViewModel.CoverKind.PREVIEW);
                onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click());
                assertTrue("selected generated URI did not produce preview", preview.await(10, TimeUnit.SECONDS));
                screenshot(scenario, "r2b-preview-portrait-light.png");
                onView(withId(R.id.trip_cover_upload)).perform(scrollTo(), click());
                assertTrue("fixture did not receive multipart upload", fixture.coverUploadRead.await(10, TimeUnit.SECONDS));
                assertTrue("authenticated display file was not read", fixture.coverFileRead.await(10, TimeUnit.SECONDS));
                screenshot(scenario, "r2b-content-portrait-light.png");
                awaitConfigurationChange(scenario,
                    activity -> ((android.app.UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES),
                    configuration -> (configuration.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                screenshot(scenario, "r2b-content-portrait-dark.png");
                assertPortraitUnderDeviceRotation(scenario, 1);
                onView(withId(R.id.trip_cover_remove)).perform(scrollTo(), click());
                onView(withText(R.string.trip_confirm_cover_remove_title)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                screenshot(scenario, "r2b-remove-dialog-portrait-rotation-dark.png");
                onView(withText(R.string.trip_confirm_cancel)).perform(click());
                assertPortraitUnderDeviceRotation(scenario, 3);
                awaitConfigurationChange(scenario,
                    activity -> ((android.app.UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO),
                    configuration -> (configuration.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO);
                screenshot(scenario, "r2b-content-portrait-rotation-light.png");
                scenario.recreate(); scenario.onActivity(activity -> assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation));
            } finally {
                InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().delete(generated, null, null); ((android.app.UiModeManager) InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(Context.UI_MODE_SERVICE)).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO);
                if ("1".equals(originalAutoRotation)) shell("cmd window user-rotation free"); else shell("cmd window user-rotation lock " + originalUserRotation);
            }
        }
    }

    @Test public void authEndedAfterRenderedContentClearsAnchorAndDestroysListWithoutCrash() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true;
            AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context));
                AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api)));
                auth.set(created);
                return created;
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1);
                scenario.onActivity(activity -> auth.get().state().observeForever(state -> {
                    if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown();
                }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS));

                AtomicReference<TripViewModel> trips = new AtomicReference<>();
                CountDownLatch content = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    main.getChildFragmentManager().executePendingTransactions();
                    TripViewModel model = new ViewModelProvider(main).get(TripViewModel.class);
                    trips.set(model);
                    model.list().observeForever(state -> {
                        if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 1) {
                            content.countDown();
                        }
                    });
                });
                assertTrue("rendered trip content timed out", content.await(10, TimeUnit.SECONDS));
                awaitListPreDraw(scenario);

                scenario.onActivity(activity -> {
                    RecyclerView list = activity.findViewById(R.id.trip_list);
                    LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
                    assertEquals(1, list.getAdapter().getItemCount());
                    assertEquals(0, layout.findFirstVisibleItemPosition());
                    trips.get().saveAnchor(TRIP, -31);
                    trips.get().authEnded();
                    assertEquals(0, list.getAdapter().getItemCount());
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    androidx.fragment.app.Fragment listFragment = main.getChildFragmentManager()
                        .findFragmentById(R.id.trip_product_container);
                    main.getChildFragmentManager().beginTransaction().remove(listFragment).commitNow();
                    assertNull("auth-ended teardown must not resurrect the cleared private anchor",
                        trips.get().anchorTripId());
                });
            }
        }
    }

    @Test public void loadedPagesAndScrollAnchorAreWiredThroughTheRenderedList() throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.pagedList = true;
            AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context));
                AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api)));
                auth.set(created);
                return created;
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1);
                scenario.onActivity(activity -> auth.get().state().observeForever(state -> {
                    if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown();
                }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS));

                AtomicReference<TripViewModel> trips = new AtomicReference<>();
                CountDownLatch firstPage = new CountDownLatch(1);
                CountDownLatch secondPage = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    main.getChildFragmentManager().executePendingTransactions();
                    TripViewModel model = new ViewModelProvider(main).get(TripViewModel.class);
                    trips.set(model);
                    model.list().observeForever(state -> {
                        if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 20) firstPage.countDown();
                        if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 30) secondPage.countDown();
                    });
                    TripViewModel.ListState current = model.list().getValue();
                    if (current != null && current.kind() == TripViewModel.ListKind.CONTENT && current.trips().size() == 20) {
                        firstPage.countDown();
                    }
                });
                assertTrue(firstPage.await(10, TimeUnit.SECONDS));
                scenario.onActivity(activity ->
                    ((RecyclerView) activity.findViewById(R.id.trip_list)).smoothScrollToPosition(19));
                assertTrue("scrolling to the rendered page end must request the continuation",
                    fixture.continuationRead.await(10, TimeUnit.SECONDS));
                assertTrue(secondPage.await(10, TimeUnit.SECONDS));
                AtomicReference<String> savedAnchor = new AtomicReference<>();
                AtomicReference<Integer> savedOffset = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    assertNotNull("the rendered list must save a stable visible-row anchor", trips.get().anchorTripId());
                    boolean anchorIsLoaded = false;
                    for (AuthApiClient.Trip trip : trips.get().list().getValue().trips()) {
                        if (trip.tripId().equals(trips.get().anchorTripId())) anchorIsLoaded = true;
                    }
                    assertTrue(anchorIsLoaded);
                    savedAnchor.set(trips.get().anchorTripId());
                    savedOffset.set(trips.get().anchorOffset());
                });

                onView(withId(R.id.trip_create_action)).perform(scrollTo(), click());
                scenario.onActivity(activity -> {
                    androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                    main.getChildFragmentManager().executePendingTransactions();
                    assertTrue(main.getChildFragmentManager().getBackStackEntryCount() == 1);
                });
                androidx.test.espresso.Espresso.pressBack();
                onView(withId(R.id.trip_list)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                scenario.onActivity(activity -> {
                    RecyclerView list = activity.findViewById(R.id.trip_list);
                    LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
                    int position = layout.findFirstVisibleItemPosition();
                    assertTrue(position >= 0);
                    assertTrue(trips.get().list().getValue().trips().get(position).tripId().equals(savedAnchor.get()));
                    assertTrue(layout.findViewByPosition(position) != null);
                    assertTrue(layout.getDecoratedTop(layout.findViewByPosition(position)) == savedOffset.get());
                });
            }
        }
    }

    private static void awaitListPreDraw(ActivityScenario<MainActivity> scenario) throws Exception {
        CountDownLatch preDraw = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.trip_list);
            list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    list.getViewTreeObserver().removeOnPreDrawListener(this);
                    preDraw.countDown();
                    return true;
                }
            });
            list.invalidate();
        });
        assertTrue("trip list pre-draw timed out", preDraw.await(10, TimeUnit.SECONDS));
    }

    private interface DelayedInvalidation { void invalidate(ActivityScenario<MainActivity> scenario, TripViewModel model) throws Exception; }
    private static void assertDelayedPickerInvalidation(DelayedInvalidation invalidation, String expectedTripId) throws Exception {
        try (UiFixture fixture = new UiFixture()) {
            fixture.createdTrip = true; AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedInSession(api))); auth.set(created); return created; });
            android.net.Uri generated = generatedJpeg(); clearPrivateCover(); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1); CountDownLatch discarded = new CountDownLatch(1);
            TripDetailFragment.installDebugPicker(generated); TripDetailFragment.installDebugPreparationHook(new TripDetailFragment.DebugPreparationHook() {
                @Override public void beforePreparation() throws Exception { entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { assertTrue(release.await(10, TimeUnit.SECONDS)); } }
                @Override public void discarded() { discarded.countDown(); }
            });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitSignedInAndOpenTrip(scenario, auth, fixture); TripViewModel model = tripModel(scenario); onView(withId(R.id.trip_cover_select)).perform(scrollTo(), click()); assertTrue("preparation did not enter controlled delay", entered.await(10, TimeUnit.SECONDS));
                invalidation.invalidate(scenario, model); release.countDown(); assertTrue("late prepared input was not disposed", discarded.await(10, TimeUnit.SECONDS)); assertNoPrivateCover();
                if (expectedTripId != null) assertEquals(expectedTripId, model.selectedTripId());
            } finally { InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver().delete(generated, null, null); }
        }
    }

    private static void awaitSignedInAndOpenTrip(ActivityScenario<MainActivity> scenario, AtomicReference<AuthViewModel> auth, UiFixture fixture) throws Exception {
        CountDownLatch signedIn = new CountDownLatch(1); scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); }));
        assertTrue(signedIn.await(10, TimeUnit.SECONDS)); assertTrue(fixture.listRead.await(10, TimeUnit.SECONDS));
        AtomicReference<Boolean> portrait = new AtomicReference<>(false); scenario.onActivity(activity -> portrait.set(activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT));
        if (!portrait.get()) awaitConfigurationChange(scenario, activity -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT), configuration -> configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT);
        scenario.onActivity(activity -> activity.findViewById(R.id.trip_row).performClick());
        assertTrue(fixture.basketRead.await(10, TimeUnit.SECONDS));
    }

    private static void awaitCoverDrawable(ActivityScenario<MainActivity> scenario) throws Exception {
        CountDownLatch rendered = new CountDownLatch(1); scenario.onActivity(activity -> {
            android.widget.ImageView image = activity.findViewById(R.id.trip_cover_image); image.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() { if (image.getDrawable() == null) return true; image.getViewTreeObserver().removeOnPreDrawListener(this); rendered.countDown(); return true; }
            }); image.invalidate();
        }); assertTrue("authenticated cover bitmap did not render", rendered.await(10, TimeUnit.SECONDS));
    }

    private static CountDownLatch observeCreatedListAfter(ActivityScenario<MainActivity> scenario,
            AtomicReference<TripViewModel.ListState> lastState) {
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            androidx.fragment.app.Fragment main = activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
            TripViewModel model = new ViewModelProvider(main).get(TripViewModel.class);
            @SuppressWarnings("unchecked") Observer<TripViewModel.ListState>[] holder = new Observer[1];
            holder[0] = state -> {
                lastState.set(state);
                if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 1) {
                    model.list().removeObserver(holder[0]);
                    latch.countDown();
                }
            };
            model.list().observeForever(holder[0]);
        });
        return latch;
    }

    private static void recreateAndAwait(ActivityScenario<MainActivity> scenario) throws Exception {
        AtomicReference<MainActivity> previous = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        Application application = (Application) InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity && activity != previous.get()) resumed.countDown();
            }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        application.registerActivityLifecycleCallbacks(lifecycle);
        try {
            scenario.onActivity(previous::set);
            scenario.recreate();
            assertTrue("Activity recreation timed out", resumed.await(10, TimeUnit.SECONDS));
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycle);
        }
    }

    private static void assertPortraitUnderDeviceRotation(ActivityScenario<MainActivity> scenario, int rotation) throws Exception {
        shell("cmd window user-rotation lock " + rotation); shell("cmd window wait-for-all-windows-drawn");
        scenario.onActivity(activity -> assertEquals("STOG host must remain portrait under device rotation " + rotation,
            android.content.res.Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation));
    }

    private static String shell(String command) throws IOException {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) { input.transferTo(output); return output.toString(StandardCharsets.UTF_8); }
    }

    private static void awaitConfigurationChange(ActivityScenario<MainActivity> scenario,
            androidx.core.util.Consumer<MainActivity> action,
            java.util.function.Predicate<android.content.res.Configuration> expected) throws Exception {
        Application application = (Application) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        AtomicReference<MainActivity> previous = new AtomicReference<>(); CountDownLatch resumed = new CountDownLatch(1);
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { if (activity instanceof MainActivity && activity != previous.get() && expected.test(activity.getResources().getConfiguration())) resumed.countDown(); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            scenario.onActivity(activity -> { previous.set(activity); action.accept(activity); }); assertTrue("configuration change timed out", resumed.await(20, TimeUnit.SECONDS));
            CountDownLatch drawn = new CountDownLatch(1); scenario.onActivity(activity -> {
                android.view.View root = activity.getWindow().getDecorView(); root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() { root.getViewTreeObserver().removeOnPreDrawListener(this); drawn.countDown(); return true; }
                }); root.invalidate();
            }); assertTrue("configured surface draw timed out", drawn.await(20, TimeUnit.SECONDS));
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks); }
    }

    private static Application.ActivityLifecycleCallbacks lifecycleCallbacks(
            androidx.core.util.Consumer<Activity> destroyed) {
        return new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { destroyed.accept(activity); }
        };
    }

    private static void screenshot(ActivityScenario<MainActivity> scenario) { screenshot(scenario, "mvp-trip-detail-empty.png"); }
    private static void screenshot(ActivityScenario<MainActivity> scenario, String name) {
        CountDownLatch committed = new CountDownLatch(1); scenario.onActivity(activity -> { android.view.View root = activity.getWindow().getDecorView(); root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate(); });
        try { if (!committed.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Screenshot frame commit timed out"); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); if (bitmap == null) throw new IllegalStateException("No UI screenshot");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name); values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/STOG-R2b-QA");
        android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) { if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException(); }
        catch (IOException exception) { throw new IllegalStateException(exception); } finally { bitmap.recycle(); }
    }
    private static android.net.Uri generatedJpeg() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "r2b-generated-cover.jpg"); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/STOG-R2b-QA");
        android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IOException("Cannot create generated image");
        Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.RGB_565); bitmap.eraseColor(0xff335f53);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) { if (output == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException("Cannot write generated image"); }
        finally { bitmap.recycle(); }
        return uri;
    }
    private static TripViewModel tripModel(ActivityScenario<MainActivity> scenario) {
        AtomicReference<TripViewModel> model = new AtomicReference<>();
        scenario.onActivity(activity -> model.set(new ViewModelProvider(activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container)).get(TripViewModel.class)));
        return model.get();
    }
    private static CountDownLatch observeCover(TripViewModel model, TripViewModel.CoverKind kind) {
        CountDownLatch latch = new CountDownLatch(1); @SuppressWarnings("unchecked") Observer<TripViewModel.CoverState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { holder[0] = value -> { if (value.kind() == kind) { model.cover().removeObserver(holder[0]); latch.countDown(); } }; model.cover().observeForever(holder[0]); }); return latch;
    }
    private static void clearPrivateCover() {
        File root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "private-cover"); File[] files = root.listFiles(); if (files != null) for (File file : files) file.delete(); root.delete();
    }
    private static void assertNoPrivateCover() {
        File root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "private-cover"); File[] files = root.listFiles(); assertTrue("private cover temp must be empty", files == null || files.length == 0);
    }
    private static final class SignedInSession implements AuthStateMachine.SessionGateway {
        private final AuthApiClient api;
        SignedInSession(AuthApiClient api) { this.api = api; }
        @Override public void restore(AuthStateMachine.CompletionCallback callback) { api.exchange("fixture", callback); }
        @Override public void exchange(String token, AuthStateMachine.CompletionCallback callback) { callback.completed(true); }
        @Override public void logout(AuthStateMachine.CompletionCallback callback) { callback.completed(true); }
    }
    private static final class UiFixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch exchangeRead = new CountDownLatch(1);
        final CountDownLatch listRead = new CountDownLatch(1);
        final CountDownLatch basketRead = new CountDownLatch(1);
        final CountDownLatch continuationRead = new CountDownLatch(1);
        final CountDownLatch coverUploadRead = new CountDownLatch(1);
        final CountDownLatch coverFileRead = new CountDownLatch(1);
        volatile boolean pagedList;
        volatile boolean createdTrip;
        volatile boolean withCover;
        volatile boolean denyTripDetail;
        UiFixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        private void serve() { while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); } catch (IOException ignored) { } }
        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream(); String headers = headers(input); String first = headers.substring(0, headers.indexOf("\r\n")); String path = first.split(" ")[1];
            byte[] body = readBody(input, length(headers));
            if (path.equals("/api/auth/kakao/exchange")) { exchangeRead.countDown(); json(socket, 200, tokens()); return; }
            if (path.equals("/api/trips?limit=20")) {
                listRead.countDown();
                json(socket, 200, pagedList ? tripPage(1, 20, "cursor1") : createdTrip ? createdTripPage()
                    : "{\"as_of_date\":\"2026-10-01\",\"items\":[],\"next_cursor\":null}");
                return;
            }
            if (path.equals("/api/trips?limit=20&cursor=cursor1") && pagedList) {
                continuationRead.countDown();
                json(socket, 200, tripPage(21, 30, null));
                return;
            }
            if (path.equals("/api/trips") && first.startsWith("POST")) {
                createdTrip = true;
                json(socket, 201, trip());
                return;
            }
            if (path.equals("/api/trips/" + TRIP + "/cover") && first.startsWith("POST")) {
                String multipart = new String(body, StandardCharsets.ISO_8859_1);
                if (!headers.contains("Authorization: Bearer access") || !multipart.contains("name=\"image\"") || !multipart.contains("name=\"command\"") || !multipart.contains("expected_plan_version")) throw new IOException("Unexpected cover multipart");
                withCover = true; coverUploadRead.countDown(); json(socket, 201, coverSet()); return;
            }
            if (path.equals("/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/file")) { jpeg(socket); coverFileRead.countDown(); return; }
            if (path.equals("/api/trips/" + TRIP)) { if (denyTripDetail) json(socket, 404, "{\"error\":{\"code\":\"TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE\",\"message\":\"safe\"}}"); else json(socket, 200, trip()); return; }
            if (path.startsWith("/api/trips/" + TRIP + "/trip-items?state=BASKET")) { basketRead.countDown(); json(socket, 200, "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":0,\"items\":[],\"next_cursor\":null}"); return; }
            json(socket, 404, "{\"error\":{\"code\":\"TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE\",\"message\":\"safe\"}}");
        }
        private String trip() { return "{\"trip_id\":\"" + TRIP + "\",\"name\":\"제주 여행\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\",\"as_of_date\":\"2026-10-01\",\"role\":\"ADMIN\",\"member_count\":1,\"plan_version\":0,\"cover\":" + (withCover ? coverJson() : "null") + "}"; }
        private String createdTripPage() {
            return "{\"as_of_date\":\"2026-10-01\",\"items\":[{\"trip_id\":\"" + TRIP
                + "\",\"name\":\"제주 여행\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\","
                + "\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\",\"role\":\"ADMIN\","
                + "\"member_count\":1,\"plan_version\":0,\"cover\":" + (withCover ? coverJson() : "null") + "}],\"next_cursor\":null}";
        }
        private static String tripPage(int first, int last, String cursor) {
            StringBuilder items = new StringBuilder();
            for (int number = first; number <= last; number++) {
                if (items.length() > 0) items.append(',');
                items.append("{\"trip_id\":\"").append(String.format(Locale.ROOT, "%08x", number))
                    .append("-b025-4f72-b420-2ee354a514ad\",\"name\":\"Trip ").append(number)
                    .append("\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\",\"role\":\"ADMIN\",\"member_count\":1,\"plan_version\":0,\"cover\":null}");
            }
            return "{\"as_of_date\":\"2026-10-01\",\"items\":[" + items + "],\"next_cursor\":"
                + (cursor == null ? "null" : "\"" + cursor + "\"") + "}";
        }
        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; }
        private static String coverJson() { return "{\"cover_id\":\"b9d8ae64-654a-4fe3-a569-a7c9f8706524\",\"media_type\":\"image/jpeg\",\"byte_size\":633,\"width\":1,\"height\":1,\"thumbnail_byte_size\":633,\"thumbnail_width\":1,\"thumbnail_height\":1,\"file_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/file\",\"thumbnail_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/thumbnail\"}"; }
        private static String coverSet() { return "{\"result\":\"SET\",\"cover\":" + coverJson() + ",\"plan_version\":1}"; }
        private static void json(Socket socket, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush(); }
        private static void jpeg(Socket socket) throws IOException { byte[] bytes = java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAEFAqf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/Aaf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/Aaf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAY/Ar//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/If/EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQMBAT8QH//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQIBAT8QH//Z"); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush(); }
        private static int length(String headers) { String value = header(headers, "content-length"); return value == null ? 0 : Integer.parseInt(value); }
        private static byte[] readBody(InputStream input, int count) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(count); while (count > 0) { byte[] buffer = new byte[Math.min(4096, count)]; int read = input.read(buffer); if (read < 0) throw new IOException(); output.write(buffer, 0, read); count -= read; } return output.toByteArray(); }
        private static String header(String headers, String name) { for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0, colon).toLowerCase(Locale.ROOT).equals(name)) return line.substring(colon + 1).trim(); } return null; }
        private static String headers(InputStream input) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int matched = 0; while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException(); bytes.write(value); matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0); } return bytes.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
