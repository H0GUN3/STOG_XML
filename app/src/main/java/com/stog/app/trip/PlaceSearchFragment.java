package com.stog.app.trip;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentPlaceSearchBinding;
import com.stog.app.databinding.ItemPlaceBinding;

import java.util.List;

public final class PlaceSearchFragment extends Fragment {
    private FragmentPlaceSearchBinding binding;
    private TripViewModel model;
    private PlaceAdapter adapter;
    private RecyclerView.OnScrollListener scrollListener;
    private TextWatcher watcher;
    private boolean anchorRestored;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentPlaceSearchBinding.inflate(inflater, container, false); return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        binding.placeQueryInput.setText(model.placeDraftQuery()); binding.placeRegionInput.setText(model.placeDraftRegion()); binding.placeCategoryInput.setText(model.placeDraftCategory());
        watcher = new TextWatcher() { @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { } @Override public void onTextChanged(CharSequence s, int start, int before, int count) { persistDraft(); } @Override public void afterTextChanged(Editable s) { } };
        binding.placeQueryInput.addTextChangedListener(watcher); binding.placeRegionInput.addTextChangedListener(watcher); binding.placeCategoryInput.addTextChangedListener(watcher);
        adapter = new PlaceAdapter(place -> { captureAnchor(); model.selectPlace(place.placeId()); openDetail(); });
        binding.placeSearchResults.setAdapter(adapter);
        scrollListener = new RecyclerView.OnScrollListener() { @Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) { captureAnchor(); if (dy > 0 && !recycler.canScrollVertically(1)) model.loadNextPlaces(); } };
        binding.placeSearchResults.addOnScrollListener(scrollListener);
        binding.placeSearchSubmit.setOnClickListener(ignored -> submit());
        binding.placeSearchClear.setOnClickListener(ignored -> { binding.placeQueryInput.setText(""); binding.placeRegionInput.setText(""); binding.placeCategoryInput.setText(""); model.clearPlaceFlow(); });
        binding.placeSearchRetry.setOnClickListener(ignored -> model.submitPlaceSearch());
        binding.placeQueryInput.setOnEditorActionListener((text, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); return true; } return false; });
        model.placeSearch().observe(getViewLifecycleOwner(), this::render);
        model.restoreOrSearchPlaces();
    }

    private void submit() { persistDraft(); model.submitPlaceSearch(); }
    private void persistDraft() { if (binding != null) model.updatePlaceDraft(text(binding.placeQueryInput), text(binding.placeRegionInput), text(binding.placeCategoryInput)); }
    private static String text(android.widget.EditText input) { return input.getText() == null ? "" : input.getText().toString(); }
    private void openDetail() { requireParentFragment().getChildFragmentManager().beginTransaction().replace(R.id.trip_product_container, new PlaceDetailFragment()).addToBackStack(null).commit(); }

    private void render(TripViewModel.PlaceSearchState state) {
        adapter.submit(state.places()); restoreAnchor(state.places());
        boolean loading = state.kind() == TripViewModel.PlaceSearchKind.LOADING;
        binding.placeSearchProgress.setVisibility(loading ? View.VISIBLE : View.GONE); binding.placeSearchSubmit.setEnabled(!loading);
        binding.placeSearchRetry.setVisibility(state.kind() == TripViewModel.PlaceSearchKind.RETRYABLE_ERROR ? View.VISIBLE : View.GONE);
        int message = switch (state.kind()) { case IDLE -> R.string.place_search_idle; case INVALID_QUERY -> R.string.place_search_invalid; case LOADING -> R.string.place_search_loading; case EMPTY -> R.string.place_search_empty; case RETRYABLE_ERROR -> R.string.place_search_error; case AUTH_ENDED -> R.string.place_search_auth_ended; default -> 0; };
        binding.placeSearchStatus.setText(message == 0 ? "" : getString(message));
    }

    private void captureAnchor() {
        if (binding == null || adapter.getItemCount() == 0) return; LinearLayoutManager layout = (LinearLayoutManager) binding.placeSearchResults.getLayoutManager(); int position = layout.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return; View row = layout.findViewByPosition(position); if (row != null) model.savePlaceAnchor(adapter.id(position), layout.getDecoratedTop(row));
    }
    private void restoreAnchor(List<AuthApiClient.Place> items) {
        if (anchorRestored || items.isEmpty() || model.placeAnchorId() == null) return; int position = adapter.positionOf(model.placeAnchorId()); if (position < 0) return;
        anchorRestored = true; binding.placeSearchResults.post(() -> { if (binding != null) ((LinearLayoutManager) binding.placeSearchResults.getLayoutManager()).scrollToPositionWithOffset(position, model.placeAnchorOffset()); });
    }

    @Override public void onDestroyView() {
        persistDraft(); captureAnchor(); binding.placeQueryInput.removeTextChangedListener(watcher); binding.placeRegionInput.removeTextChangedListener(watcher); binding.placeCategoryInput.removeTextChangedListener(watcher); binding.placeQueryInput.setOnEditorActionListener(null);
        binding.placeSearchResults.removeOnScrollListener(scrollListener); binding.placeSearchResults.setAdapter(null); binding.placeSearchSubmit.setOnClickListener(null); binding.placeSearchClear.setOnClickListener(null); binding.placeSearchRetry.setOnClickListener(null); binding = null; super.onDestroyView();
    }

    private interface Selected { void select(AuthApiClient.Place place); }
    private static final class PlaceAdapter extends RecyclerView.Adapter<Holder> {
        private final Selected selected; private List<AuthApiClient.Place> items = List.of();
        PlaceAdapter(Selected selected) { this.selected = selected; setHasStableIds(true); }
        void submit(List<AuthApiClient.Place> next) { List<AuthApiClient.Place> old = items; List<AuthApiClient.Place> replacement = List.copyOf(next); DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() { @Override public int getOldListSize() { return old.size(); } @Override public int getNewListSize() { return replacement.size(); } @Override public boolean areItemsTheSame(int a, int b) { return old.get(a).placeId().equals(replacement.get(b).placeId()); } @Override public boolean areContentsTheSame(int a, int b) { return old.get(a).equals(replacement.get(b)); } }); items = replacement; diff.dispatchUpdatesTo(this); }
        String id(int position) { return items.get(position).placeId(); } int positionOf(String id) { for (int i = 0; i < items.size(); i++) if (items.get(i).placeId().equals(id)) return i; return -1; }
        @Override public long getItemId(int position) { java.util.UUID id = java.util.UUID.fromString(items.get(position).placeId()); return id.getMostSignificantBits() ^ id.getLeastSignificantBits(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(ItemPlaceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(items.get(position), selected); }
        @Override public int getItemCount() { return items.size(); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        private final ItemPlaceBinding binding; Holder(ItemPlaceBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(AuthApiClient.Place place, Selected selected) { android.content.Context context = binding.getRoot().getContext(); String category = place.category() == null ? context.getString(R.string.place_category_missing) : place.category(); String region = place.regionLabel() == null ? context.getString(R.string.place_region_missing) : place.regionLabel(); String address = place.address() == null ? context.getString(R.string.place_address_missing) : place.address(); binding.placeRowName.setText(place.name()); binding.placeRowMeta.setText(context.getString(R.string.place_result_meta, category, region, address)); binding.placeRow.setOnClickListener(ignored -> selected.select(place)); }
    }
}
