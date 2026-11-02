package com.stog.app.trip;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentPlaceDetailBinding;

import java.util.Locale;

public final class PlaceDetailFragment extends Fragment {
    private FragmentPlaceDetailBinding binding;
    private TripViewModel model;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentPlaceDetailBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        binding.placeDetailRetry.setOnClickListener(ignored -> model.loadPlaceDetail()); binding.placeSaveAction.setOnClickListener(ignored -> model.saveSelectedPlace());
        model.placeDetail().observe(getViewLifecycleOwner(), this::renderDetail); model.placeSave().observe(getViewLifecycleOwner(), this::renderSave); model.detail().observe(getViewLifecycleOwner(), ignored -> updateSaveEnabled());
        TripViewModel.PlaceDetailState current = model.placeDetail().getValue(); if (current == null || current.kind() == TripViewModel.PlaceDetailKind.IDLE) model.loadPlaceDetail();
    }
    private void renderDetail(TripViewModel.PlaceDetailState state) {
        boolean loading = state.kind() == TripViewModel.PlaceDetailKind.LOADING; binding.placeDetailProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.placeDetailRetry.setVisibility(state.kind() == TripViewModel.PlaceDetailKind.NOT_FOUND || state.kind() == TripViewModel.PlaceDetailKind.RETRYABLE_ERROR ? View.VISIBLE : View.GONE);
        binding.placeSaveAction.setVisibility(state.kind() == TripViewModel.PlaceDetailKind.CONTENT && model.selectedTripId() != null ? View.VISIBLE : View.GONE);
        if (state.place() == null) { binding.placeDetailName.setText(switch (state.kind()) { case LOADING -> R.string.place_detail_loading; case NOT_FOUND -> R.string.place_detail_not_found; case AUTH_ENDED -> R.string.place_search_auth_ended; default -> R.string.place_detail_error; }); binding.placeDetailMeta.setText(""); binding.placeDetailDescription.setText(""); binding.placeCoordinateStatus.setText(""); return; }
        AuthApiClient.Place place = state.place(); String category = fallback(place.category(), R.string.place_category_missing); String region = fallback(place.regionLabel(), R.string.place_region_missing); String address = fallback(place.address(), R.string.place_address_missing); String road = fallback(place.roadAddress(), R.string.place_road_address_missing);
        binding.placeDetailName.setText(place.name()); binding.placeDetailMeta.setText(getString(R.string.place_detail_meta, category, region, address, road)); binding.placeDetailDescription.setText(fallback(place.description(), R.string.place_description_missing));
        binding.placeCoordinateStatus.setText(place.latitude() == null ? R.string.place_coordinates_missing : 0); if (place.latitude() != null) binding.placeCoordinateStatus.setText(String.format(Locale.ROOT, "%.6f, %.6f", place.latitude(), place.longitude())); updateSaveEnabled();
    }
    private String fallback(String value, int resource) { return value == null ? getString(resource) : value; }
    private void renderSave(TripViewModel.PlaceSaveState state) {
        boolean saving = state.kind() == TripViewModel.PlaceSaveKind.SAVING;
        int message = switch (state.kind()) { case SAVING -> R.string.place_save_saving; case CREATED -> R.string.place_save_created; case ALREADY_EXISTS -> R.string.place_save_exists; case VERSION_CONFLICT -> R.string.place_save_conflict; case TRIP_ENDED -> R.string.place_save_trip_ended; case TRIP_INACCESSIBLE -> R.string.place_save_trip_inaccessible; case RETRYABLE_ERROR -> R.string.place_save_error; case AUTH_ENDED -> R.string.place_search_auth_ended; default -> 0; };
        if (model.selectedTripId() == null) message = R.string.place_save_needs_trip;
        binding.placeSaveStatus.setText(message == 0 ? "" : getString(message)); updateSaveEnabled();
    }
    private void updateSaveEnabled() { if (binding == null) return; TripViewModel.PlaceSaveState save = model.placeSave().getValue(); TripViewModel.DetailState trip = model.detail().getValue(); binding.placeSaveAction.setEnabled(save != null && save.kind() != TripViewModel.PlaceSaveKind.SAVING && trip != null && trip.kind() == TripViewModel.DetailKind.CONTENT && trip.trip() != null); }
    @Override public void onDestroyView() { binding.placeDetailRetry.setOnClickListener(null); binding.placeSaveAction.setOnClickListener(null); binding = null; super.onDestroyView(); }
}
