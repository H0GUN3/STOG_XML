package com.stog.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentHomeBinding;
import com.stog.app.databinding.ItemTripBinding;
import com.stog.app.databinding.ViewHomeStatBinding;
import com.stog.app.trip.TripCard;
import com.stog.app.trip.TripViewModel;

import java.util.ArrayList;
import java.util.List;

/** Real signed-in landing state backed by the same authenticated trip list as My Trips. */
public final class HomeFragment extends Fragment {
    private static final int MAX_TRIP_CARDS = 3;

    private FragmentHomeBinding binding;
    private TripViewModel model;
    private int cardGeneration;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        bindStat(binding.homeStatTrips, R.drawable.ic_trips_24, R.string.home_stat_trips);
        bindStat(binding.homeStatCells, R.drawable.ic_cell_24, R.string.home_stat_cells);
        bindStat(binding.homeStatRegions, R.drawable.ic_place_24, R.string.home_stat_regions);
        // No CELL or visit record exists until the recording feature ships, so both counts are truly zero.
        binding.homeStatCells.homeStatValue.setText(getString(R.string.home_stat_count, 0));
        binding.homeStatRegions.homeStatValue.setText(getString(R.string.home_stat_count, 0));
        binding.homeCreateAction.setOnClickListener(ignored -> ((MainFragment) requireParentFragment()).openTripCreate());
        binding.homeRetryAction.setOnClickListener(ignored -> model.loadTrips());
        model.list().observe(getViewLifecycleOwner(), this::render);
        model.displayName().observe(getViewLifecycleOwner(), name -> binding.homeGreeting.setText(
                name == null ? getString(R.string.home_greeting) : getString(R.string.home_greeting_named, name)));
        model.loadDisplayName();
        if (model.list().getValue() == null || model.list().getValue().kind() == TripViewModel.ListKind.IDLE
                || model.list().getValue().kind() == TripViewModel.ListKind.LOADING) model.loadTrips();
    }

    private void bindStat(ViewHomeStatBinding stat, int icon, int label) {
        stat.homeStatIcon.setImageResource(icon);
        stat.homeStatLabel.setText(label);
    }

    private void render(TripViewModel.ListState state) {
        boolean loading = state.kind() == TripViewModel.ListKind.LOADING || state.kind() == TripViewModel.ListKind.IDLE;
        boolean content = state.kind() == TripViewModel.ListKind.CONTENT && !state.trips().isEmpty();
        boolean empty = state.kind() == TripViewModel.ListKind.EMPTY;
        boolean error = state.kind() == TripViewModel.ListKind.ERROR || state.kind() == TripViewModel.ListKind.AUTH_ENDED;
        List<AuthApiClient.Trip> active = activeTrips(state.trips());
        boolean noActive = empty || (content && active.isEmpty());
        binding.homeProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.homeStatTrips.homeStatValue.setText(content || empty
                ? getString(state.nextCursor() == null ? R.string.home_stat_count : R.string.home_stat_count_more, state.trips().size())
                : getString(R.string.home_stat_unknown));
        binding.homeTripSection.setVisibility(content && !active.isEmpty() ? View.VISIBLE : View.GONE);
        binding.homeEmptyGroup.setVisibility(noActive ? View.VISIBLE : View.GONE);
        binding.homeEmptyTitle.setText(empty ? R.string.home_empty_title : R.string.home_no_active_title);
        binding.homeEmptyBody.setText(empty ? R.string.home_empty_body : R.string.home_no_active_body);
        binding.homeCreateAction.setContentDescription(getString(empty ? R.string.home_create_action : R.string.home_create_next_action));
        binding.homeErrorGroup.setVisibility(error ? View.VISIBLE : View.GONE);
        binding.homeTripCards.removeAllViews();
        cardGeneration++;
        if (content) for (AuthApiClient.Trip trip : active) addTripCard(trip);
    }

    private List<AuthApiClient.Trip> activeTrips(List<AuthApiClient.Trip> trips) {
        List<AuthApiClient.Trip> active = new ArrayList<>();
        for (AuthApiClient.Trip trip : trips) {
            if (active.size() == MAX_TRIP_CARDS) break;
            if (!"PAST".equals(trip.status())) active.add(trip);
        }
        return active;
    }

    private void addTripCard(AuthApiClient.Trip trip) {
        ItemTripBinding card = ItemTripBinding.inflate(getLayoutInflater(), binding.homeTripCards, true);
        int generation = cardGeneration;
        TripCard.bind(card, trip, (cover, callback) -> model.loadCoverImage(cover, true, result -> {
            if (binding == null || generation != cardGeneration) {
                if (result.bitmap() != null) result.bitmap().recycle();
                return;
            }
            callback.completed(result);
        }));
        card.tripRow.setOnClickListener(ignored -> {
            model.select(trip.tripId());
            ((MainFragment) requireParentFragment()).openSelectedTrip();
        });
    }

    @Override public void onDestroyView() {
        binding.homeCreateAction.setOnClickListener(null);
        binding.homeRetryAction.setOnClickListener(null);
        binding.homeTripCards.removeAllViews();
        binding = null;
        super.onDestroyView();
    }
}
