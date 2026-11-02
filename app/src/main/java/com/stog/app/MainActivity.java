package com.stog.app;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.stog.app.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getSupportFragmentManager().beginTransaction()
            .setPrimaryNavigationFragment(getSupportFragmentManager().findFragmentById(R.id.fragment_container))
            .commitNow();

        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        dispatchInvitationIntent(getIntent());
    }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent); dispatchInvitationIntent(intent);
    }
    private void dispatchInvitationIntent(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (fragment instanceof MainFragment main) main.receiveInvitation(intent.getDataString());
    }
}
