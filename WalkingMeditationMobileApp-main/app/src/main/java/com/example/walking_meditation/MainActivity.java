package com.example.walking_meditation;

import android.content.Intent;
import com.example.walking_meditation.audio.CurrentWalk;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.StudyConfigClient;
import com.example.walking_meditation.utils.SessionManager;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private Button btnExplore, btnResearcherDashboard, btnStudy, btnFeedback, btnAdminDashboard, btnBecomeResearcher;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnExplore = findViewById(R.id.btnExplore);
        findViewById(R.id.btnResumeWalk).setOnClickListener(v ->
                startActivity(new Intent(this, MeditationPlaybackActivity.class)));
        btnResearcherDashboard = findViewById(R.id.btnResearcherDashboard);
        btnStudy = findViewById(R.id.btnStudy);
        btnFeedback = findViewById(R.id.btnFeedback);
        btnAdminDashboard = findViewById(R.id.btnAdminDashboard);
        btnBecomeResearcher = findViewById(R.id.btnBecomeResearcher);

        if (SessionManager.isVerifiedResearcher(this)) {
            btnResearcherDashboard.setVisibility(View.VISIBLE);
            btnResearcherDashboard.setOnClickListener(view -> {
                Intent intent = new Intent(MainActivity.this, ResearcherStudyListActivity.class);
                startActivity(intent);
            });
        }

        if (SessionManager.isAdmin(this)) {
            btnAdminDashboard.setVisibility(View.VISIBLE);
            btnAdminDashboard.setOnClickListener(view ->
                    startActivity(new Intent(MainActivity.this, AdminDashboardActivity.class))
            );
        }

        if (!SessionManager.isAdmin(this) && !"researcher".equals(SessionManager.getUserRole(this))) {
            btnBecomeResearcher.setVisibility(View.VISIBLE);
            btnBecomeResearcher.setOnClickListener(view ->
                    startActivity(new Intent(MainActivity.this, BecomeResearcherActivity.class))
            );
        }

        btnExplore.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, PathSelectionActivity.class);
            startActivity(intent);
        });

        btnStudy.setOnClickListener(view -> openStudyManagement());

        btnFeedback.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, FeedbackActivity.class))
        );
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ready;
        try { ready = new CurrentWalk(getFilesDir()).read(true).ready; }
        catch (Exception ignored) { ready = false; }
        findViewById(R.id.btnResumeWalk).setVisibility(ready ? View.VISIBLE : View.GONE);
    }

    private void openStudyManagement() {
        btnStudy.setEnabled(false);
        btnStudy.setText(R.string.checking_study_status);

        StudyConfigClient.fetchCurrent(this, httpClient, new StudyConfigClient.Listener() {
            @Override
            public void onLoaded(
                    boolean participating,
                    @NonNull String studyId,
                    @NonNull String parameters
            ) {
                runOnUiThread(() -> {
                    resetStudyButton();
                    Intent intent;
                    if (participating && !studyId.isEmpty()) {
                        intent = new Intent(MainActivity.this, StudyUserManageActivity.class);
                    } else {
                        SessionManager.clearStudyConfiguration(MainActivity.this);
                        intent = new Intent(MainActivity.this, StudyUnenrolledActivity.class);
                    }
                    startActivity(intent);
                });
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> {
                    resetStudyButton();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resetStudyButton() {
        btnStudy.setEnabled(true);
        btnStudy.setText(R.string.manage_study);
    }
}
