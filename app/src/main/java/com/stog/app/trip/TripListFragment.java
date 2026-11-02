package com.stog.app.trip;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentTripListBinding;
import com.stog.app.databinding.ItemTripBinding;

import java.util.List;

public final class TripListFragment extends Fragment {
    private FragmentTripListBinding binding;
    private TripViewModel model;
    private TripAdapter adapter;
    private RecyclerView.OnScrollListener pageListener;
    private ViewTreeObserver.OnPreDrawListener pendingAnchorRestore;
    private boolean anchorRestored;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTripListBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        adapter = new TripAdapter(trip -> {
            captureAnchor();
            model.select(trip.tripId());
            open(new TripDetailFragment());
        }, (cover, callback) -> model.loadCoverImage(cover, true, callback));
        binding.tripList.setAdapter(adapter);
        pageListener = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                captureAnchor();
                if (dy > 0 && !view.canScrollVertically(1)) model.loadNextTrips();
            }
        };
        binding.tripList.addOnScrollListener(pageListener);
        binding.tripCreateAction.setOnClickListener(ignored -> {
            captureAnchor();
            open(new TripCreateFragment());
        });
        binding.tripJoinAction.setOnClickListener(ignored -> open(new TripJoinFragment()));
        binding.tripListRetry.setOnClickListener(ignored -> model.loadTrips());
        model.list().observe(getViewLifecycleOwner(), this::render);
        if (model.list().getValue().kind() == TripViewModel.ListKind.IDLE
                || model.list().getValue().kind() == TripViewModel.ListKind.LOADING) model.loadTrips();
    }
    private void render(TripViewModel.ListState state) {
        adapter.submit(state.trips());
        if (state.kind() == TripViewModel.ListKind.AUTH_ENDED) cancelPendingAnchorRestore();
        else restoreAnchorAfterLayout(state);
        boolean loading = state.kind() == TripViewModel.ListKind.LOADING;
        binding.tripListProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.tripListRetry.setVisibility(state.kind() == TripViewModel.ListKind.ERROR ? View.VISIBLE : View.GONE);
        int text = switch (state.kind()) {
            case EMPTY -> R.string.trips_empty; case ERROR -> R.string.trips_error; case AUTH_ENDED -> R.string.trips_auth_ended;
            case LOADING -> R.string.trips_loading; default -> 0;
        };
        binding.tripListStatus.setText(text == 0 ? "" : getString(text));
    }
    private void captureAnchor() {
        if (binding == null || model.list().getValue() == null
                || model.list().getValue().kind() == TripViewModel.ListKind.AUTH_ENDED) return;
        LinearLayoutManager layout = (LinearLayoutManager) binding.tripList.getLayoutManager();
        int position = layout.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) return;
        View row = layout.findViewByPosition(position);
        if (row != null) model.saveAnchor(adapter.tripId(position), layout.getDecoratedTop(row));
    }

    private void restoreAnchorAfterLayout(TripViewModel.ListState state) {
        String anchorTripId = model.anchorTripId();
        if (anchorRestored || state.trips().isEmpty() || anchorTripId == null) return;
        int position = adapter.positionOf(anchorTripId);
        if (position < 0) return;
        anchorRestored = true;
        pendingAnchorRestore = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                cancelPendingAnchorRestore();
                String currentAnchor = model.anchorTripId();
                if (binding == null || currentAnchor == null || position >= adapter.getItemCount()
                        || !currentAnchor.equals(adapter.tripId(position))) return true;
                LinearLayoutManager layout = (LinearLayoutManager) binding.tripList.getLayoutManager();
                layout.scrollToPositionWithOffset(position, model.anchorOffset());
                return true;
            }
        };
        binding.tripList.getViewTreeObserver().addOnPreDrawListener(pendingAnchorRestore);
        binding.tripList.invalidate();
    }

    private void cancelPendingAnchorRestore() {
        if (pendingAnchorRestore == null) return;
        if (binding != null) {
            ViewTreeObserver observer = binding.tripList.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(pendingAnchorRestore);
        }
        pendingAnchorRestore = null;
    }

    private void open(Fragment fragment) {
        requireParentFragment().getChildFragmentManager().beginTransaction()
            .replace(R.id.trip_product_container, fragment).addToBackStack(null).commit();
    }
    @Override public void onDestroyView() {
        captureAnchor();
        cancelPendingAnchorRestore();
        binding.tripCreateAction.setOnClickListener(null); binding.tripJoinAction.setOnClickListener(null); binding.tripListRetry.setOnClickListener(null);
        binding.tripList.removeOnScrollListener(pageListener);
        binding.tripList.setAdapter(null);
        pageListener = null; binding = null; super.onDestroyView();
    }

    private static final class TripAdapter extends RecyclerView.Adapter<TripHolder> {
        private final TripSelected selected;
        private final TripCard.CoverLoader thumbnails;
        private List<AuthApiClient.Trip> items = List.of();
        TripAdapter(TripSelected selected, TripCard.CoverLoader thumbnails) { this.selected = selected; this.thumbnails = thumbnails; setHasStableIds(true); }
        void submit(List<AuthApiClient.Trip> next) {
            List<AuthApiClient.Trip> previous = items;
            List<AuthApiClient.Trip> replacement = List.copyOf(next);
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return previous.size(); }
                @Override public int getNewListSize() { return replacement.size(); }
                @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    return previous.get(oldPosition).tripId().equals(replacement.get(newPosition).tripId());
                }
                @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    return previous.get(oldPosition).equals(replacement.get(newPosition));
                }
            });
            items = replacement;
            diff.dispatchUpdatesTo(this);
        }
        String tripId(int position) { return items.get(position).tripId(); }
        int positionOf(String tripId) {
            for (int position = 0; position < items.size(); position++) {
                if (items.get(position).tripId().equals(tripId)) return position;
            }
            return -1;
        }
        @NonNull @Override public TripHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new TripHolder(ItemTripBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull TripHolder holder, int position) { holder.bind(items.get(position), selected, thumbnails); }
        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) {
            java.util.UUID id = java.util.UUID.fromString(items.get(position).tripId());
            return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        }
    }
    private static final class TripHolder extends RecyclerView.ViewHolder {
        private final ItemTripBinding binding;
        TripHolder(ItemTripBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(AuthApiClient.Trip trip, TripSelected selected, TripCard.CoverLoader thumbnails) {
            TripCard.bind(binding, trip, thumbnails);
            binding.tripRow.setOnClickListener(view -> selected.selected(trip));
        }
    }

    private interface TripSelected { void selected(AuthApiClient.Trip trip); }
}
