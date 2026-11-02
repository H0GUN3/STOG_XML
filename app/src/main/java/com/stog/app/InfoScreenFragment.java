package com.stog.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.stog.app.databinding.FragmentInfoScreenBinding;

/** Honest empty screen for a destination whose feature does not exist yet. */
public final class InfoScreenFragment extends Fragment {
    private static final String TITLE = "title";
    private static final String BODY = "body";
    private FragmentInfoScreenBinding binding;

    /** Pass 0 as the title when the toolbar already names the screen. */
    static InfoScreenFragment create(@StringRes int title, @StringRes int body) {
        InfoScreenFragment fragment = new InfoScreenFragment();
        Bundle arguments = new Bundle();
        arguments.putInt(TITLE, title);
        arguments.putInt(BODY, body);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentInfoScreenBinding.inflate(inflater, container, false);
        int title = requireArguments().getInt(TITLE);
        if (title == 0) binding.infoTitle.setVisibility(View.GONE);
        else binding.infoTitle.setText(title);
        binding.infoBody.setText(requireArguments().getInt(BODY));
        return binding.getRoot();
    }

    @Override public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
