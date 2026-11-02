package com.stog.app.trip;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.FragmentTripMembersBinding;
import com.stog.app.databinding.ItemTripMemberBinding;

import java.util.List;

public final class TripMembersFragment extends Fragment {
    private FragmentTripMembersBinding binding;
    private TripViewModel model;
    private MemberAdapter adapter;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTripMembersBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(TripViewModel.class);
        adapter = new MemberAdapter(this::confirmRole, this::confirmRemove);
        binding.tripMembersList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.tripMembersList.setAdapter(adapter);
        binding.tripMembersRetry.setOnClickListener(ignored -> model.loadMembers());
        model.members().observe(getViewLifecycleOwner(), this::render);
        model.loadMembers();
    }
    private void render(TripViewModel.MembersState state) {
        boolean loading = state.kind() == TripViewModel.MembersKind.LOADING;
        binding.tripMembersProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.tripMembersRetry.setVisibility(state.kind() == TripViewModel.MembersKind.ERROR ? View.VISIBLE : View.GONE);
        boolean admin = model.detail().getValue() != null && model.detail().getValue().trip() != null
            && "ADMIN".equals(model.detail().getValue().trip().role());
        adapter.submit(state.page() == null ? List.of() : state.page().items(), admin);
        binding.tripMembersStatus.setText(switch (state.kind()) {
            case LOADING -> getString(R.string.trip_members_loading); case EMPTY -> getString(R.string.trip_members_empty);
            case ERROR -> getString(R.string.trips_error); case AUTH_ENDED -> getString(R.string.trips_auth_ended); default -> "";
        });
    }
    private void confirmRole(AuthApiClient.Member member) {
        String nextRole = "ADMIN".equals(member.role()) ? "MEMBER" : "ADMIN";
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.trip_confirm_role_title)
            .setMessage(getString(R.string.trip_member_role, roleLabel(nextRole))).setNegativeButton(R.string.trip_confirm_cancel, null)
            .setPositiveButton(R.string.trip_confirm_continue, (dialog, which) -> model.changeRole(member, nextRole)).show();
    }
    private void confirmRemove(AuthApiClient.Member member) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.trip_confirm_remove_title).setMessage(R.string.trip_confirm_body)
            .setNegativeButton(R.string.trip_confirm_cancel, null).setPositiveButton(R.string.trip_confirm_continue, (dialog, which) -> model.removeMember(member)).show();
    }
    @Override public void onDestroyView() {
        binding.tripMembersRetry.setOnClickListener(null); binding.tripMembersList.setAdapter(null); binding = null; super.onDestroyView();
    }

    private static final class MemberAdapter extends RecyclerView.Adapter<MemberHolder> {
        private final Action role; private final Action remove; private List<AuthApiClient.Member> items = List.of(); private boolean admin;
        MemberAdapter(Action role, Action remove) { this.role = role; this.remove = remove; setHasStableIds(true); }
        void submit(List<AuthApiClient.Member> next, boolean isAdmin) {
            int previous = items.size(); items = List.copyOf(next); admin = isAdmin;
            if (previous > 0) notifyItemRangeRemoved(0, previous);
            if (!items.isEmpty()) notifyItemRangeInserted(0, items.size());
        }
        @NonNull @Override public MemberHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new MemberHolder(ItemTripMemberBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
        @Override public void onBindViewHolder(@NonNull MemberHolder holder, int position) { holder.bind(items.get(position), position, admin, role, remove); }
        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) { java.util.UUID id = java.util.UUID.fromString(items.get(position).memberUserId()); return id.getMostSignificantBits() ^ id.getLeastSignificantBits(); }
    }
    private static final class MemberHolder extends RecyclerView.ViewHolder {
        private final ItemTripMemberBinding binding;
        MemberHolder(ItemTripMemberBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(AuthApiClient.Member member, int position, boolean admin, Action role, Action remove) {
            String name = member.self() ? binding.getRoot().getContext().getString(R.string.trip_member_you)
                : member.memberLabel() == null ? binding.getRoot().getContext().getString(R.string.trip_member_fallback, position + 1) : member.memberLabel();
            binding.tripMemberName.setText(name); binding.tripMemberRole.setText(binding.getRoot().getContext().getString(R.string.trip_member_role, roleLabel(binding.getRoot().getContext(), member.role())));
            binding.tripMemberRoleAction.setVisibility(admin ? View.VISIBLE : View.GONE); binding.tripMemberRemoveAction.setVisibility(admin ? View.VISIBLE : View.GONE);
            binding.tripMemberRoleAction.setOnClickListener(admin ? view -> role.action(member) : null); binding.tripMemberRemoveAction.setOnClickListener(admin ? view -> remove.action(member) : null);
        }
    }
    private static String roleLabel(String role) { return "ADMIN".equals(role) ? "관리자" : "구성원"; }
    private static String roleLabel(android.content.Context context, String role) { return context.getString("ADMIN".equals(role) ? R.string.trip_role_admin : R.string.trip_role_member); }
    private interface Action { void action(AuthApiClient.Member member); }
}
