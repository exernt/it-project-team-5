package com.example.walking_meditation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.StudyApiClient;

import okhttp3.OkHttpClient;

public class StudyDetailsActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";
    private static final String EXTRA_STUDY_NAME = "study_name";
    private static final String EXTRA_STUDY_DURATION = "study_duration";
    private static final String EXTRA_STUDY_DESCRIPTION = "study_description";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private ProgressBar progress;
    private LinearLayout content;
    private TextView nameView;
    private TextView durationView;
    private TextView descriptionView;
    private TextView messageView;
    private Button joinButton;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull Study study) {
        return createIntent(context, study.getId())
                .putExtra(EXTRA_STUDY_NAME, study.getName())
                .putExtra(EXTRA_STUDY_DURATION, study.getDuration())
                .putExtra(EXTRA_STUDY_DESCRIPTION, study.getDescription());
    }

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String studyId) {
        return new Intent(context, StudyDetailsActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_details);
        setTitle(R.string.study_details_title);

        progress = findViewById(R.id.studyDetailsProgress);
        content = findViewById(R.id.studyDetailsContent);
        nameView = findViewById(R.id.studyName);
        durationView = findViewById(R.id.studyDuration);
        descriptionView = findViewById(R.id.studyDescription);
        messageView = findViewById(R.id.studyDetailsMessage);
        joinButton = findViewById(R.id.btnJoinStudy);

        findViewById(R.id.btnStudyBack).setOnClickListener(view -> returnToStudyList());
        joinButton.setOnClickListener(view -> joinStudy());

        Intent intent = getIntent();
        String requestedStudyId = intent.getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }

        String studyName = intent.getStringExtra(EXTRA_STUDY_NAME);
        int studyDuration = intent.getIntExtra(EXTRA_STUDY_DURATION, -1);
        String studyDescription = intent.getStringExtra(EXTRA_STUDY_DESCRIPTION);
        if (studyName != null && studyDuration > 0 && studyDescription != null) {
            showStudy(studyName, studyDuration, studyDescription);
            return;
        }

        if (studyId.isEmpty()) {
            showError(getString(R.string.study_details_error));
            return;
        }
        loadStudy();
    }

    private void loadStudy() {
        StudyApiClient.fetchStudy(this, httpClient, studyId, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(() -> showStudy(
                        study.getName(),
                        study.getDuration(),
                        study.getDescription()
                ));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> showError(
                        error.isEmpty() ? getString(R.string.study_details_error) : error
                ));
            }
        });
    }

    private void joinStudy() {
        if (studyId.isEmpty()) {
            return;
        }
        joinButton.setEnabled(false);
        joinButton.setText(R.string.joining_study);
        StudyApiClient.joinStudy(this, httpClient, studyId, new StudyApiClient.JoinListener() {
            @Override
            public void onJoined() {
                runOnUiThread(() -> {
                    SessionManager.clearStudyConfiguration(StudyDetailsActivity.this);
                    startActivity(new Intent(
                            StudyDetailsActivity.this,
                            StudyUserManageActivity.class
                    ));
                    finish();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    joinButton.setEnabled(true);
                    joinButton.setText(R.string.join_study);
                    Toast.makeText(StudyDetailsActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void returnToStudyList() {
        Intent intent = new Intent(this, StudyViewListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showStudy(
            @NonNull String studyName,
            int studyDuration,
            @NonNull String studyDescription
    ) {
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        nameView.setText(studyName);
        durationView.setText(getResources().getQuantityString(
                R.plurals.study_duration_days,
                studyDuration,
                studyDuration
        ));
        if (studyDescription.isEmpty()) {
            descriptionView.setText(R.string.study_description_empty);
        } else {
            descriptionView.setText(studyDescription);
        }
        content.setVisibility(View.VISIBLE);
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        messageView.setText(error);
        messageView.setVisibility(View.VISIBLE);
    }
}
