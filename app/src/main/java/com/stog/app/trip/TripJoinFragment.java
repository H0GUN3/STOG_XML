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
import com.stog.app.databinding.FragmentTripJoinBinding;

/** Explicit confirmation screen: parsing alone never joins a trip. */
public final class TripJoinFragment extends Fragment {
    private FragmentTripJoinBinding binding;
    private TripViewModel model;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTripJoinBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        binding.tripJoinSubmit.setOnClickListener(ignored -> submit());
        model.collaboration().observe(getViewLifecycleOwner(), value -> {
            binding.tripJoinSubmit.setEnabled(value.kind() != TripViewModel.CollaborationKind.LOADING);
            binding.tripJoinStatus.setText(message(value.kind()));
            if (value.kind() == TripViewModel.CollaborationKind.SUCCESS) requireParentFragment().getChildFragmentManager().popBackStack();
        });
    }
    private void submit() {
        if (!model.hasPendingJoin() && !model.prepareJoin(text(binding.tripJoinLink))) {
            binding.tripJoinStatus.setText(R.string.trip_join_invalid); return;
        }
        model.submitJoin(text(binding.tripJoinMemberLabel));
    }
    private String message(TripViewModel.CollaborationKind kind) {
        return switch (kind) {
            case INVALID_INPUT -> getString(R.string.trip_join_invalid);
            case LOADING -> getString(R.string.trip_collaboration_loading);
            case INVITATION_UNUSABLE -> getString(R.string.trip_collaboration_invite_unusable);
            case RETRYABLE_ERROR -> getString(R.string.trip_collaboration_retryable);
            case AUTH_ENDED -> getString(R.string.trips_auth_ended);
            default -> "";
        };
    }
    private static String text(android.widget.EditText field) { return field.getText() == null ? "" : field.getText().toString(); }
    @Override public void onDestroyView() { binding.tripJoinSubmit.setOnClickListener(null); binding = null; super.onDestroyView(); }
}
