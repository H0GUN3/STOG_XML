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
import com.stog.app.databinding.FragmentTripEditBinding;

public final class TripEditFragment extends Fragment {
    private FragmentTripEditBinding binding;
    private TripViewModel model;
    private TripDatesController dates;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTripEditBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        model.beginEdit();
        TripViewModel.EditDraft draft = model.editDraft();
        binding.tripEditName.setText(draft.name()); binding.tripEditRegion.setText(draft.regionLabel());
        binding.tripEditStart.setText(draft.startDate()); binding.tripEditEnd.setText(draft.endDate());
        dates = new TripDatesController(binding.tripDatesCard, binding.tripEditStart, binding.tripEditEnd, state);
        binding.tripEditSubmit.setOnClickListener(ignored -> {
            model.updateEditDraft(text(binding.tripEditName), text(binding.tripEditRegion), text(binding.tripEditStart), text(binding.tripEditEnd));
            model.submitEdit();
        });
        model.collaboration().observe(getViewLifecycleOwner(), stateValue -> {
            binding.tripEditSubmit.setEnabled(stateValue.kind() != TripViewModel.CollaborationKind.LOADING);
            binding.tripEditStatus.setText(message(stateValue.kind()));
            if (stateValue.kind() == TripViewModel.CollaborationKind.SUCCESS) requireParentFragment().getChildFragmentManager().popBackStack();
        });
    }
    private String message(TripViewModel.CollaborationKind kind) {
        return switch (kind) {
            case INVALID_INPUT -> getString(R.string.trip_create_invalid);
            case LOADING -> getString(R.string.trip_collaboration_loading);
            case VERSION_CONFLICT -> getString(R.string.trip_collaboration_conflict);
            case DATE_RANGE_CONFLICT -> getString(R.string.trip_collaboration_date_conflict);
            case ADMIN_REQUIRED -> getString(R.string.trip_collaboration_forbidden);
            case LAST_ADMIN_REQUIRED -> getString(R.string.trip_collaboration_last_admin);
            case RETRYABLE_ERROR -> getString(R.string.trip_collaboration_retryable);
            default -> "";
        };
    }
    private static String text(android.widget.EditText field) { return field.getText() == null ? "" : field.getText().toString(); }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (binding != null) dates.save(state);
    }
    @Override public void onDestroyView() {
        if (binding != null) {
            model.updateEditDraft(text(binding.tripEditName), text(binding.tripEditRegion), text(binding.tripEditStart), text(binding.tripEditEnd));
            dates.release();
            binding.tripEditSubmit.setOnClickListener(null); binding = null;
        }
        super.onDestroyView();
    }
}
