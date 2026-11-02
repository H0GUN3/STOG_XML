package com.stog.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.stog.app.databinding.FragmentAccountBinding;
import com.stog.app.databinding.SheetAccountMoreBinding;
import com.stog.app.trip.TripViewModel;

/** Profile header above a three-column post grid. The name and logout are real; bio, follow counts and posts are sample content. */
public final class AccountFragment extends Fragment {
    private static final int COLUMNS = 3;
    private FragmentAccountBinding binding;
    @Nullable private BottomSheetDialog moreSheet;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentAccountBinding.inflate(inflater, container, false);
        GridLayoutManager layout = new GridLayoutManager(requireContext(), COLUMNS);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return position == 0 ? COLUMNS : 1; }
        });
        binding.accountContent.setLayoutManager(layout);
        ProfileAdapter adapter = new ProfileAdapter();
        binding.accountContent.setAdapter(adapter);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        TripViewModel model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        ProfileAdapter adapter = (ProfileAdapter) binding.accountContent.getAdapter();
        model.displayName().observe(getViewLifecycleOwner(), adapter::setDisplayName);
        model.loadDisplayName();
        model.list().observe(getViewLifecycleOwner(), list -> adapter.setTripCount(
                list.kind() == TripViewModel.ListKind.CONTENT ? list.trips().size() : 0));
        TripViewModel.ListState list = model.list().getValue();
        if (list == null || list.kind() == TripViewModel.ListKind.IDLE) model.loadTrips();
    }

    void showMoreSheet() {
        SheetAccountMoreBinding sheet = SheetAccountMoreBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sheet.getRoot());
        sheet.sheetEditProfile.setOnClickListener(ignored -> {
            dialog.dismiss();
            Snackbar.make(binding.getRoot(), R.string.feature_coming_soon, Snackbar.LENGTH_SHORT).show();
        });
        sheet.sheetLogout.setOnClickListener(ignored -> {
            dialog.dismiss();
            ((MainFragment) requireParentFragment()).logout();
        });
        moreSheet = dialog;
        dialog.show();
    }

    @Override public void onDestroyView() {
        if (moreSheet != null) moreSheet.dismiss();
        moreSheet = null;
        binding.accountContent.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    private static final class ProfileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER = 0;
        private static final int PHOTO = 1;
        @Nullable private String displayName;
        private int tripCount;

        void setTripCount(int value) {
            tripCount = value;
            notifyItemChanged(0);
        }

        void setDisplayName(@Nullable String value) {
            displayName = value;
            notifyItemChanged(0);
        }

        @Override public int getItemViewType(int position) { return position == 0 ? HEADER : PHOTO; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view = inflater.inflate(type == HEADER ? R.layout.item_profile_header : R.layout.item_profile_photo, parent, false);
            if (type == PHOTO) view.getLayoutParams().height = parent.getMeasuredWidth() / COLUMNS;
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View view = holder.itemView;
            if (position == 0) {
                ((TextView) view.findViewById(R.id.profile_name)).setText(displayName);
                ((TextView) view.findViewById(R.id.profile_post_count)).setText(String.valueOf(SamplePosts.ALL.length));
                bindStat(view.findViewById(R.id.profile_stat_trips), R.drawable.ic_trips_24, R.string.home_stat_trips, tripCount);
                bindStat(view.findViewById(R.id.profile_stat_cells), R.drawable.ic_cell_24, R.string.home_stat_cells, 0);
                bindStat(view.findViewById(R.id.profile_stat_regions), R.drawable.ic_place_24, R.string.home_stat_regions, 0);
                return;
            }
            int[] post = SamplePosts.ALL[position - 1];
            ImageView photo = (ImageView) view;
            photo.setImageResource(post[0]);
            photo.setContentDescription(view.getContext().getString(post[2]));
        }

        @Override public int getItemCount() { return SamplePosts.ALL.length + 1; }

        private static void bindStat(View stat, int icon, int label, int value) {
            ((ImageView) stat.findViewById(R.id.home_stat_icon)).setImageResource(icon);
            ((TextView) stat.findViewById(R.id.home_stat_label)).setText(label);
            ((TextView) stat.findViewById(R.id.home_stat_value)).setText(stat.getContext().getString(R.string.home_stat_count, value));
        }
    }
}
