package com.stog.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.stog.app.auth.AuthStateMachine;
import com.stog.app.auth.AuthViewModel;
import com.stog.app.databinding.FragmentMainBinding;
import com.stog.app.trip.TripCreateFragment;
import com.stog.app.trip.TripDetailFragment;
import com.stog.app.trip.TripJoinFragment;
import com.stog.app.trip.TripListFragment;
import com.stog.app.trip.TripViewModel;
import com.stog.app.trip.PlaceSearchFragment;
import com.stog.app.trip.PlaceDetailFragment;
import com.stog.app.trip.TripEditFragment;
import com.stog.app.trip.TripMembersFragment;

/** Auth gate and the two real signed-in roots. Child trip flows remain owned by this fragment. */
public final class MainFragment extends Fragment {
    private FragmentMainBinding binding;
    private AuthViewModel auth;
    private TripViewModel trips;
    private String pendingInvitationInput;
    private boolean signedIn;
    private boolean signedOutSeen;
    private final boolean[] signupEdited = new boolean[4];
    private final boolean[] signupValidated = new boolean[4];
    private final androidx.fragment.app.FragmentManager.OnBackStackChangedListener toolbarListener = this::updateToolbar;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentMainBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        auth = new ViewModelProvider(requireActivity(), new AuthViewModel.Factory(requireContext())).get(AuthViewModel.class);
        trips = new ViewModelProvider(this, new TripViewModel.Factory(this, auth.api())).get(TripViewModel.class);
        binding.authSwitchMode.setOnClickListener(ignored -> {
            if (signupMode(auth.state().getValue())) selectLogin();
            else selectSignup();
        });
        binding.authPrimaryAction.setOnClickListener(ignored -> submit());
        watchSignupField(0, binding.authUsername);
        watchSignupField(1, binding.authPassword);
        watchSignupField(2, binding.authDisplayName);
        watchSignupField(3, binding.authPasswordConfirm);
        binding.productNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.destination_home) showRoot(new HomeFragment(), "home");
            else if (id == R.id.destination_trips) showRoot(new TripListFragment(), "trips");
            else if (id == R.id.destination_discover) showRoot(new DiscoverFragment(), "discover");
            else showRoot(new AccountFragment(), "account");
            return true;
        });
        binding.productToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_search) {
                openHomePlaceSearch();
                return true;
            }
            if (item.getItemId() == R.id.action_more) {
                Fragment current = getChildFragmentManager().findFragmentById(R.id.trip_product_container);
                if (current instanceof AccountFragment) ((AccountFragment) current).showMoreSheet();
                return true;
            }
            if (item.getItemId() == R.id.action_notifications) {
                openNotifications();
                return true;
            }
            return false;
        });
        binding.productToolbar.setNavigationOnClickListener(ignored -> {
            if (getChildFragmentManager().findFragmentById(R.id.trip_product_container) instanceof AccountFragment) {
                com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), R.string.feature_coming_soon,
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            } else {
                getChildFragmentManager().popBackStack();
            }
        });
        getChildFragmentManager().addOnBackStackChangedListener(toolbarListener);
        auth.state().observe(getViewLifecycleOwner(), this::render);
        auth.start();
    }

    private void updateToolbar() {
        if (binding == null) return;
        Fragment current = getChildFragmentManager().findFragmentById(R.id.trip_product_container);
        boolean nested = current != null && current.getTag() == null;
        boolean account = current instanceof AccountFragment;
        binding.productToolbar.setVisibility(current instanceof DiscoverFragment ? View.GONE : View.VISIBLE);
        binding.productToolbar.getMenu().findItem(R.id.action_notifications).setVisible(!account);
        binding.productToolbar.getMenu().findItem(R.id.action_more).setVisible(account);
        androidx.appcompat.widget.Toolbar.LayoutParams logoParams =
                (androidx.appcompat.widget.Toolbar.LayoutParams) binding.productToolbarLogo.getLayoutParams();
        logoParams.gravity = account ? android.view.Gravity.CENTER : android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
        binding.productToolbarLogo.setLayoutParams(logoParams);
        binding.productToolbar.getMenu().findItem(R.id.action_search).setVisible(current instanceof HomeFragment);
        if (nested) {
            binding.productToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            binding.productToolbar.setNavigationContentDescription(R.string.navigate_back);
        } else if (account) {
            binding.productToolbar.setNavigationIcon(R.drawable.ic_add_24);
            binding.productToolbar.setNavigationContentDescription(R.string.post_add);
        } else {
            binding.productToolbar.setNavigationIcon((android.graphics.drawable.Drawable) null);
            binding.productToolbar.setNavigationContentDescription((CharSequence) null);
        }
        int title = R.string.app_name;
        if (current instanceof TripDetailFragment) title = R.string.trip_detail_toolbar;
        else if (current instanceof TripCreateFragment) title = R.string.trip_create_title;
        else if (current instanceof TripJoinFragment) title = R.string.trip_join_title;
        else if (current instanceof PlaceSearchFragment) title = R.string.place_search_title;
        else if (current instanceof PlaceDetailFragment) title = R.string.place_detail_toolbar;
        else if (current instanceof TripEditFragment) title = R.string.trip_edit_toolbar;
        else if (current instanceof TripMembersFragment) title = R.string.trip_members_toolbar;
        else if (nested && current instanceof InfoScreenFragment) title = R.string.notifications_title;
        boolean showLogo = title == R.string.app_name;
        binding.productToolbarLogo.setVisibility(showLogo ? View.VISIBLE : View.GONE);
        if (showLogo) binding.productToolbar.setTitle(null);
        else binding.productToolbar.setTitle(title);
    }

    public void receiveInvitation(String raw) {
        pendingInvitationInput = raw;
        if (signedIn && trips.prepareJoin(raw)) {
            pendingInvitationInput = null;
            openJoin();
        }
    }

    void showHome() {
        if (!signedIn) return;
        if (binding.productNavigation.getSelectedItemId() != R.id.destination_home) {
            binding.productNavigation.setSelectedItemId(R.id.destination_home);
        }
        showRoot(new HomeFragment(), "home");
    }

    void logout() { auth.logout(); }

    private void openNotifications() {
        boolean alreadyOpen = getChildFragmentManager().getBackStackEntryCount() > 0
                && getChildFragmentManager().findFragmentById(R.id.trip_product_container) instanceof InfoScreenFragment;
        if (!alreadyOpen) open(InfoScreenFragment.create(0, R.string.notifications_empty));
    }

    private void openHomePlaceSearch() {
        trips.startPlaceSearchWithoutTrip();
        open(new PlaceSearchFragment());
    }

    void openTripCreate() { open(new TripCreateFragment()); }

    void openSelectedTrip() { open(new TripDetailFragment()); }

    private void selectLogin() {
        clearPasswords();
        clearSignupErrors();
        auth.showLogin();
    }

    private void selectSignup() {
        clearPasswords();
        clearSignupErrors();
        auth.showSignup();
    }

    private void submit() {
        if (signupMode(auth.state().getValue())) {
            boolean valid = true;
            for (int index = 0; index < signupValidated.length; index++) {
                signupValidated[index] = true;
                valid &= validateSignupField(index);
            }
            if (!valid) return;
            auth.signup(value(binding.authUsername), value(binding.authDisplayName), value(binding.authPassword),
                    value(binding.authPasswordConfirm));
        } else {
            auth.login(value(binding.authUsername), value(binding.authPassword));
        }
        clearSignupErrors();
        clearPasswords();
    }

    private void watchSignupField(int index, android.widget.EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!signupMode(auth.state().getValue())) return;
                signupEdited[index] = true;
                if (signupValidated[index]) validateSignupField(index);
                if (index == 1 && signupValidated[3]) validateSignupField(3);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        field.setOnFocusChangeListener((view, focused) -> {
            if (!focused && signupMode(auth.state().getValue()) && signupEdited[index]) {
                signupValidated[index] = true;
                validateSignupField(index);
            }
        });
    }

    private boolean validateSignupField(int index) {
        boolean valid;
        com.google.android.material.textfield.TextInputLayout layout;
        int hint;
        switch (index) {
            case 0 -> {
                valid = AuthViewModel.isValidUsername(AuthViewModel.canonicalUsername(value(binding.authUsername)));
                layout = binding.authUsernameLayout;
                hint = R.string.auth_username_helper;
            }
            case 1 -> {
                valid = AuthViewModel.isValidPassword(value(binding.authPassword));
                layout = binding.authPasswordLayout;
                hint = R.string.auth_password_helper;
            }
            case 2 -> {
                valid = AuthViewModel.isValidDisplayName(value(binding.authDisplayName));
                layout = binding.authDisplayNameLayout;
                hint = R.string.auth_display_name_error;
            }
            default -> {
                valid = !value(binding.authPasswordConfirm).isEmpty()
                        && value(binding.authPassword).equals(value(binding.authPasswordConfirm));
                layout = binding.authPasswordConfirmLayout;
                hint = R.string.auth_password_confirm_error;
            }
        }
        layout.setError(valid ? null : getString(hint));
        layout.setErrorEnabled(!valid);
        return valid;
    }

    private void clearSignupErrors() {
        java.util.Arrays.fill(signupEdited, false);
        java.util.Arrays.fill(signupValidated, false);
        binding.authUsernameLayout.setErrorEnabled(false);
        binding.authPasswordLayout.setErrorEnabled(false);
        binding.authDisplayNameLayout.setErrorEnabled(false);
        binding.authPasswordConfirmLayout.setErrorEnabled(false);
    }

    private void render(AuthStateMachine.State state) {
        boolean nowSignedIn = state.kind() == AuthStateMachine.Kind.SIGNED_IN;
        if (nowSignedIn && !signedIn) trips.clearEndedSession();
        signedIn = nowSignedIn;
        if (!nowSignedIn && state.kind() != AuthStateMachine.Kind.RESTORING) signedOutSeen = true;
        binding.authScroll.setVisibility(nowSignedIn ? View.GONE : View.VISIBLE);
        binding.signedInShell.setVisibility(nowSignedIn ? View.VISIBLE : View.GONE);
        if (nowSignedIn) {
            if (signedOutSeen || getChildFragmentManager().findFragmentById(R.id.trip_product_container) == null) showHome();
            signedOutSeen = false;
            if (pendingInvitationInput != null && trips.prepareJoin(pendingInvitationInput)) {
                pendingInvitationInput = null;
                openJoin();
            }
            return;
        }
        getChildFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        boolean signup = signupMode(state);
        binding.authDisplayNameLayout.setVisibility(signup ? View.VISIBLE : View.GONE);
        binding.authPasswordConfirmLayout.setVisibility(signup ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams usernameParams = (LinearLayout.LayoutParams) binding.authUsernameLayout.getLayoutParams();
        usernameParams.topMargin = getResources().getDimensionPixelSize(
                signup ? R.dimen.space_medium : R.dimen.space_extra_large);
        binding.authUsernameLayout.setLayoutParams(usernameParams);
        if (!signup) clearSignupErrors();
        boolean submitting = state.kind() == AuthStateMachine.Kind.SUBMITTING_LOGIN
                || state.kind() == AuthStateMachine.Kind.SUBMITTING_SIGNUP || state.kind() == AuthStateMachine.Kind.RESTORING
                || state.kind() == AuthStateMachine.Kind.LOGGING_OUT;
        binding.authProgress.setVisibility(submitting ? View.VISIBLE : View.GONE);
        binding.authPrimaryAction.setEnabled(!submitting && state.kind() != AuthStateMachine.Kind.MISSING_CONFIGURATION);
        binding.authPrimaryAction.setText(signup ? R.string.auth_signup_action : R.string.auth_login_action);
        binding.authSwitchMode.setText(signup ? R.string.auth_signup_login_link : R.string.auth_signup_link);
        binding.authSwitchMode.setStrokeColor(android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(binding.authSwitchMode,
                        com.google.android.material.R.attr.colorOutline)));
        binding.authSwitchMode.setStrokeWidth(signup ? 0 : getResources().getDimensionPixelSize(R.dimen.auth_button_outline));
        LinearLayout.LayoutParams actionParams = (LinearLayout.LayoutParams) binding.authPrimaryAction.getLayoutParams();
        actionParams.topMargin = getResources().getDimensionPixelSize(
                signup ? R.dimen.space_large : R.dimen.space_extra_large);
        binding.authPrimaryAction.setLayoutParams(actionParams);
        binding.authTitle.setText(signup ? R.string.auth_signup_title : R.string.auth_login_title);
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) binding.authTitle.getLayoutParams();
        titleParams.topMargin = getResources().getDimensionPixelSize(
                signup ? R.dimen.space_medium : R.dimen.space_small);
        binding.authTitle.setLayoutParams(titleParams);
        ViewGroup.LayoutParams logoParams = binding.authLogo.getLayoutParams();
        int logoSize = getResources().getDimensionPixelSize(signup ? R.dimen.signup_logo_size : R.dimen.logo_size);
        logoParams.width = logoSize;
        logoParams.height = logoSize;
        binding.authLogo.setLayoutParams(logoParams);
        binding.authPassword.setImeOptions(signup ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        int message = message(state);
        binding.authError.setText(message == 0 ? "" : getString(message));
        binding.authError.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
    }

    private int message(AuthStateMachine.State state) {
        if (state.kind() == AuthStateMachine.Kind.MISSING_CONFIGURATION) return R.string.auth_missing_configuration_body;
        if (state.kind() == AuthStateMachine.Kind.SIGNED_OUT_REVOKE_UNCONFIRMED) return R.string.auth_revoke_warning_body;
        if (state.kind() != AuthStateMachine.Kind.ERROR) return 0;
        String code = state.errorCode() == null ? "" : state.errorCode().replace(":signup", "").replace(":login", "");
        return switch (code) {
            case "PASSWORD_NOT_ACCEPTABLE" -> R.string.auth_password_not_acceptable;
            case "AUTHENTICATION_REQUIRED" -> R.string.auth_invalid_credentials;
            case "ACCOUNT_NOT_CREATED" -> R.string.auth_account_not_created;
            case "AUTH_REGISTRATION_UNAVAILABLE" -> R.string.auth_registration_unavailable;
            case "DATABASE_BUSY", "DATABASE_OPERATION_FAILED", "NETWORK_ERROR" -> R.string.auth_retryable_error;
            default -> R.string.auth_invalid_input;
        };
    }

    private boolean signupMode(AuthStateMachine.State state) {
        if (state == null) return false;
        return state.kind() == AuthStateMachine.Kind.SIGNED_OUT_SIGNUP
                || state.kind() == AuthStateMachine.Kind.SUBMITTING_SIGNUP
                || (state.kind() == AuthStateMachine.Kind.ERROR && state.errorCode() != null && state.errorCode().endsWith(":signup"));
    }

    private void showRoot(Fragment fragment, String tag) {
        Fragment current = getChildFragmentManager().findFragmentById(R.id.trip_product_container);
        if (current != null && tag.equals(current.getTag())) return;
        getChildFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getChildFragmentManager().beginTransaction().replace(R.id.trip_product_container, fragment, tag).runOnCommit(this::updateToolbar).commit();
    }

    private void open(Fragment fragment) {
        if (!signedIn) return;
        getChildFragmentManager().beginTransaction()
                .replace(R.id.trip_product_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void openJoin() {
        if (!signedIn || getChildFragmentManager().findFragmentById(R.id.trip_product_container) instanceof TripJoinFragment) return;
        open(new TripJoinFragment());
    }

    private void clearPasswords() {
        binding.authPassword.setText(null);
        binding.authPasswordConfirm.setText(null);
    }

    private static String value(android.widget.EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    @Override public void onDestroyView() {
        getChildFragmentManager().removeOnBackStackChangedListener(toolbarListener);
        binding.authSwitchMode.setOnClickListener(null);
        binding.authPrimaryAction.setOnClickListener(null);
        clearSignupErrors();
        pendingInvitationInput = null;
        binding = null;
        super.onDestroyView();
    }
}
