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
import com.stog.app.databinding.FragmentTripCreateBinding;


public final class TripCreateFragment extends Fragment {
    private FragmentTripCreateBinding binding;
    private TripViewModel model;
    private TripDatesController dates;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTripCreateBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        TripFlowState.Draft draft = model.flow().draft();
        binding.tripNameInput.setText(draft.name()); binding.tripRegionInput.setText(draft.regionLabel());
        binding.tripStartInput.setText(draft.startDate()); binding.tripEndInput.setText(draft.endDate());
        dates = new TripDatesController(binding.tripDatesCard, binding.tripStartInput, binding.tripEndInput, state);
        binding.tripCreateSubmit.setOnClickListener(ignored -> submit());
        model.create().observe(getViewLifecycleOwner(), value -> {
            boolean loading = value.kind() == TripViewModel.CreateKind.LOADING;
            binding.tripCreateSubmit.setEnabled(!loading);
            int message = switch (value.kind()) {
                case INVALID_INPUT -> R.string.trip_create_invalid;
                case LOADING -> R.string.trip_create_loading;
                case REQUEST_ID_REUSED -> R.string.trip_create_request_reused;
                case RETRYABLE_ERROR -> R.string.trip_create_error;
                case AUTH_ENDED -> R.string.trips_auth_ended;
                default -> 0;
            };
            binding.tripCreateStatus.setText(message == 0 ? "" : getString(message));
        });
        model.detail().observe(getViewLifecycleOwner(), value -> {
            if (value.kind() == TripViewModel.DetailKind.CONTENT && value.trip() != null && model.consumeCreatedTrip()) {
                binding.tripNameInput.setText(""); binding.tripRegionInput.setText("");
                binding.tripStartInput.setText(""); binding.tripEndInput.setText("");
                requireParentFragment().getChildFragmentManager().popBackStack();
                requireParentFragment().getChildFragmentManager().beginTransaction().replace(R.id.trip_product_container,
                    new TripDetailFragment()).addToBackStack(null).commit();
            }
        });
    }
    private void submit() {
        model.flow().updateDraft(text(binding.tripNameInput), text(binding.tripRegionInput), text(binding.tripStartInput), text(binding.tripEndInput));
        model.persistDraft();
        model.submitCreate();
    }
    private static String text(android.widget.EditText input) { return input.getText() == null ? "" : input.getText().toString(); }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (binding != null) dates.save(state);
    }
    @Override public void onDestroyView() {
        if (binding != null) {
            model.flow().updateDraft(text(binding.tripNameInput), text(binding.tripRegionInput), text(binding.tripStartInput), text(binding.tripEndInput));
            model.persistDraft();
            dates.release();
            binding.tripCreateSubmit.setOnClickListener(null); binding = null;
        }
        super.onDestroyView();
    }
}
