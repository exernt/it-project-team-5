package com.example.walking_meditation;

import android.content.Intent;
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

    private Button btnExplore, btnResearcherDashboard, btnStudy;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnExplore = findViewById(R.id.btnExplore);
        btnResearcherDashboard = findViewById(R.id.btnResearcherDashboard);
        btnStudy = findViewById(R.id.btnStudy);

        if (SessionManager.isVerifiedResearcher(this)) {
            btnResearcherDashboard.setVisibility(View.VISIBLE);
            btnResearcherDashboard.setOnClickListener(view -> {
                Intent intent = new Intent(MainActivity.this, ResearcherStudyListActivity.class);
                startActivity(intent);
            });
        }

        btnExplore.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, PathSelectionActivity.class);
            startActivity(intent);
        });

        btnStudy.setOnClickListener(view -> openStudyManagement());
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
