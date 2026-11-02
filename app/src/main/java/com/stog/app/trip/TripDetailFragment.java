package com.stog.app.trip;

import android.content.Intent;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stog.app.BuildConfig;
import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentTripDetailBinding;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TripDetailFragment extends Fragment {
    private FragmentTripDetailBinding binding;
    private TripViewModel model;
    private final ExecutorService coverWorker = Executors.newSingleThreadExecutor();
    private long imageGeneration;
    private long selectionGeneration;
    private Bitmap displayedCoverBitmap;
    private static volatile Uri debugPickerUri;
    private static volatile DebugPreparationHook debugPreparationHook;
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::selectedDocument);

    /** Instrumentation-only seam; release builds cannot consume an injected URI. */
    public interface DebugPreparationHook { void beforePreparation() throws Exception; void discarded(); }
    public static void installDebugPicker(Uri uri) { if (BuildConfig.DEBUG) debugPickerUri = uri; }
    public static void installDebugPreparationHook(DebugPreparationHook hook) { if (BuildConfig.DEBUG) debugPreparationHook = hook; }
    public static void clearDebugPicker() { debugPickerUri = null; debugPreparationHook = null; }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentTripDetailBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        binding.tripDetailRetry.setOnClickListener(ignored -> model.loadDetail()); binding.tripAddPlace.setOnClickListener(ignored -> open(new PlaceSearchFragment())); binding.tripDetailEdit.setOnClickListener(ignored -> open(new TripEditFragment())); binding.tripDetailMembers.setOnClickListener(ignored -> open(new TripMembersFragment())); binding.tripDetailShare.setOnClickListener(ignored -> model.shareInvitation()); binding.tripDetailStopShare.setOnClickListener(ignored -> model.stopInvitation()); binding.tripDetailLeave.setOnClickListener(ignored -> confirm(R.string.trip_confirm_leave_title, model::leaveTrip)); binding.tripDetailDelete.setOnClickListener(ignored -> confirm(R.string.trip_confirm_delete_title, model::deleteTrip));
        binding.tripCoverSelect.setOnClickListener(ignored -> { long selection = ++selectionGeneration; Uri injected = BuildConfig.DEBUG ? debugPickerUri : null; if (injected != null) selectedDocument(injected, selection); else picker.launch(new String[]{"image/jpeg", "image/png"}); }); binding.tripCoverUpload.setOnClickListener(ignored -> model.submitCover()); binding.tripCoverRemove.setOnClickListener(ignored -> confirm(R.string.trip_confirm_cover_remove_title, model::removeCover));
        model.detail().observe(getViewLifecycleOwner(), this::render); model.collaboration().observe(getViewLifecycleOwner(), this::renderCollaboration); model.cover().observe(getViewLifecycleOwner(), this::renderCover); model.loadDetail();
    }
    private void selectedDocument(Uri uri) { selectedDocument(uri, selectionGeneration); }
    private void selectedDocument(Uri uri, long selection) {
        if (uri == null || !selectionCurrent(selection, model == null ? null : model.selectedTripId())) { if (binding != null) model.coverPermissionDenied(); return; }
        final String tripId = model.selectedTripId();
        ContentResolver resolver = requireContext().getApplicationContext().getContentResolver(); String type = resolver.getType(uri);
        if (!("image/jpeg".equals(type) || "image/png".equals(type))) { model.coverPermissionDenied(); return; }
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
        android.content.Context appContext = requireContext().getApplicationContext(); android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        coverWorker.execute(() -> {
            try {
                DebugPreparationHook hook = BuildConfig.DEBUG ? debugPreparationHook : null; if (hook != null) hook.beforePreparation();
                CoverInput.Prepared prepared = CoverInput.copyAndPreview(resolver, uri, type, new File(appContext.getCacheDir(), "private-cover"));
                main.post(() -> { if (selectionCurrent(selection, tripId)) model.selectCover(prepared); else disposePrepared(prepared); });
            } catch (Exception exception) { main.post(() -> { if (selectionCurrent(selection, tripId)) model.coverPermissionDenied(); }); }
            finally { try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { } }
        });
    }
    private boolean selectionCurrent(long selection, String tripId) { return binding != null && isAdded() && selection == selectionGeneration && tripId != null && tripId.equals(model.selectedTripId()); }
    private static void disposePrepared(CoverInput.Prepared prepared) { if (prepared.preview() != null && !prepared.preview().isRecycled()) prepared.preview().recycle(); prepared.file().delete(); DebugPreparationHook hook = BuildConfig.DEBUG ? debugPreparationHook : null; if (hook != null) hook.discarded(); }
    private void render(TripViewModel.DetailState state) {
        if (state.kind() == TripViewModel.DetailKind.IDLE && model.selectedTripId() == null) { requireParentFragment().getChildFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE); return; }
        boolean loading = state.kind() == TripViewModel.DetailKind.LOADING; binding.tripDetailProgress.setVisibility(loading ? View.VISIBLE : View.GONE); binding.tripDetailRetry.setVisibility(state.kind() == TripViewModel.DetailKind.ERROR ? View.VISIBLE : View.GONE);
        binding.tripAddPlace.setVisibility(state.kind() == TripViewModel.DetailKind.CONTENT && state.trip() != null ? View.VISIBLE : View.GONE);
        if (state.trip() == null) { invalidateAndClearDisplayedCover(); binding.tripDetailName.setText(loading ? R.string.trip_detail_loading : R.string.trips_error); binding.tripDetailMeta.setText(""); binding.tripBasketStatus.setText(""); binding.tripCollaborationCard.setVisibility(View.GONE); return; }
         AuthApiClient.Trip trip = state.trip(); binding.tripDetailName.setText(trip.name() == null ? getString(R.string.trip_title_missing) : trip.name()); String region = trip.regionLabel() == null ? getString(R.string.trip_region_missing) : trip.regionLabel(); binding.tripDetailMeta.setText(getString(R.string.trip_detail_meta, region, trip.startDate(), trip.endDate(), status(trip.status()), role(trip.role()), trip.memberCount()));
        if (state.basket() == null) binding.tripBasketStatus.setText(R.string.trip_basket_error); else if (state.basket().items().isEmpty()) binding.tripBasketStatus.setText(R.string.trip_basket_empty); else { StringBuilder text = new StringBuilder(); for (AuthApiClient.BasketItem item : state.basket().items()) { if (text.length() > 0) text.append('\n'); text.append(getString(R.string.trip_basket_item)); } binding.tripBasketStatus.setText(text); }
        boolean admin = "ADMIN".equals(trip.role()); binding.tripCollaborationCard.setVisibility(View.VISIBLE); binding.tripDetailEdit.setVisibility(View.VISIBLE); binding.tripDetailMembers.setVisibility(admin ? View.VISIBLE : View.GONE); binding.tripDetailShare.setVisibility(admin ? View.VISIBLE : View.GONE); binding.tripDetailStopShare.setVisibility(admin ? View.VISIBLE : View.GONE); binding.tripDetailDelete.setVisibility(admin ? View.VISIBLE : View.GONE); binding.tripDetailLeave.setVisibility(admin ? View.GONE : View.VISIBLE);
        loadDisplayCover(trip.cover());
    }
    private String status(String value) { return getString(switch (value) { case "UPCOMING" -> R.string.trip_status_upcoming; case "PAST" -> R.string.trip_status_past; default -> R.string.trip_status_ongoing; }); }
    private String role(String value) { return getString("ADMIN".equals(value) ? R.string.trip_role_admin : R.string.trip_role_member); }
    private void loadDisplayCover(AuthApiClient.Cover value) {
        final long request = ++imageGeneration; clearDisplayedCover();
        if (value == null) { binding.tripCoverStatus.setText(R.string.trip_cover_missing); binding.tripCoverRemove.setEnabled(false); return; }
        binding.tripCoverStatus.setText(R.string.trip_cover_loading); binding.tripCoverRemove.setEnabled(true);
        model.loadCoverImage(value, false, result -> {
            if (binding == null || request != imageGeneration) { if (result.bitmap() != null) result.bitmap().recycle(); return; }
            clearDisplayedCover();
            if (result.outcome() == AuthApiClient.CoverImageOutcome.CONTENT) { displayedCoverBitmap = result.bitmap(); binding.tripCoverImage.setImageBitmap(displayedCoverBitmap); binding.tripCoverImage.setVisibility(View.VISIBLE); binding.tripCoverStatus.setText(""); }
            else binding.tripCoverStatus.setText(result.outcome() == AuthApiClient.CoverImageOutcome.MISSING ? R.string.trip_cover_missing : R.string.trip_cover_error);
        });
    }
    private void renderCover(TripViewModel.CoverState state) {
        if (binding == null) return; boolean uploading = state.kind() == TripViewModel.CoverKind.UPLOADING; binding.tripCoverSelect.setEnabled(!uploading); binding.tripCoverUpload.setEnabled(state.kind() == TripViewModel.CoverKind.PREVIEW || state.kind() == TripViewModel.CoverKind.ERROR);
        if (state.preview() != null) { imageGeneration++; clearDisplayedCover(); binding.tripCoverImage.setImageBitmap(state.preview()); binding.tripCoverImage.setVisibility(View.VISIBLE); }
        else if (state.kind() == TripViewModel.CoverKind.MISSING || state.kind() == TripViewModel.CoverKind.AUTH_ENDED) invalidateAndClearDisplayedCover();
        int message = switch (state.kind()) { case PREVIEW -> R.string.trip_cover_preview; case UPLOADING -> R.string.trip_cover_uploading; case MISSING -> R.string.trip_cover_missing; case ERROR -> R.string.trip_cover_error; case PERMISSION_DENIED -> R.string.trip_cover_permission; case VERSION_CONFLICT -> R.string.trip_cover_conflict; default -> 0; }; if (message != 0) binding.tripCoverStatus.setText(message);
    }
    private void invalidateAndClearDisplayedCover() { imageGeneration++; clearDisplayedCover(); }
    private void clearDisplayedCover() {
        if (binding != null) { binding.tripCoverImage.setImageDrawable(null); binding.tripCoverImage.setVisibility(View.GONE); }
        if (displayedCoverBitmap != null && !displayedCoverBitmap.isRecycled()) displayedCoverBitmap.recycle();
        displayedCoverBitmap = null;
    }
    private void renderCollaboration(TripViewModel.CollaborationState state) { binding.tripDetailCollaborationStatus.setText(switch (state.kind()) { case LOADING -> getString(R.string.trip_collaboration_loading); case RETRYABLE_ERROR -> getString(R.string.trip_collaboration_retryable); case VERSION_CONFLICT -> getString(R.string.trip_collaboration_conflict); case DATE_RANGE_CONFLICT -> getString(R.string.trip_collaboration_date_conflict); case ADMIN_REQUIRED -> getString(R.string.trip_collaboration_forbidden); case LAST_ADMIN_REQUIRED -> getString(R.string.trip_collaboration_last_admin); case INVITATION_UNUSABLE -> getString(R.string.trip_collaboration_invite_unusable); default -> ""; }); String inviteUri = model.consumePendingShareUri(); if (inviteUri != null) { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteUri), getString(R.string.trip_share_chooser))); model.invitationShared(); } }
    private void confirm(int title, Runnable action) { new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(R.string.trip_confirm_body).setNegativeButton(R.string.trip_confirm_cancel, null).setPositiveButton(R.string.trip_confirm_continue, (dialog, which) -> action.run()).show(); }
    private void open(Fragment fragment) { requireParentFragment().getChildFragmentManager().beginTransaction().replace(R.id.trip_product_container, fragment).addToBackStack(null).commit(); }
    @Override public void onDestroyView() { invalidateAndClearDisplayedCover(); selectionGeneration++; binding.tripDetailRetry.setOnClickListener(null); binding.tripAddPlace.setOnClickListener(null); binding.tripDetailEdit.setOnClickListener(null); binding.tripDetailMembers.setOnClickListener(null); binding.tripDetailShare.setOnClickListener(null); binding.tripDetailStopShare.setOnClickListener(null); binding.tripDetailLeave.setOnClickListener(null); binding.tripDetailDelete.setOnClickListener(null); binding.tripCoverSelect.setOnClickListener(null); binding.tripCoverUpload.setOnClickListener(null); binding.tripCoverRemove.setOnClickListener(null); binding = null; super.onDestroyView(); }
    @Override public void onDestroy() { coverWorker.shutdownNow(); super.onDestroy(); }
}
