package com.example.walking_meditation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.StudyApiClient;
import com.example.walking_meditation.utils.StudyLifecycle;

import okhttp3.OkHttpClient;

public class ResearcherStudyDetailActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private String currentStatus = "";

    private ProgressBar progress;
    private ScrollView content;
    private TextView statusView;
    private TextView messageView;
    private EditText nameField;
    private EditText durationField;
    private EditText descriptionField;
    private Button saveButton;
    private Button lifecycleButton;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String studyId) {
        return new Intent(context, ResearcherStudyDetailActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_study_detail);
        setTitle(R.string.researcher_study_list_title);

        progress = findViewById(R.id.researcherDetailProgress);
        content = findViewById(R.id.researcherDetailContent);
        statusView = findViewById(R.id.researcherDetailStatus);
        messageView = findViewById(R.id.researcherDetailMessage);
        nameField = findViewById(R.id.researcherDetailName);
        durationField = findViewById(R.id.researcherDetailDuration);
        descriptionField = findViewById(R.id.researcherDetailDescription);
        saveButton = findViewById(R.id.btnResearcherDetailSave);
        lifecycleButton = findViewById(R.id.btnResearcherDetailLifecycle);

        findViewById(R.id.btnResearcherDetailBack).setOnClickListener(view -> finish());
        saveButton.setOnClickListener(view -> saveChanges());

        String requestedStudyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }

        if (studyId.isEmpty()) {
            showError(getString(R.string.researcher_detail_error));
            return;
        }
        loadStudy();
        findViewById(R.id.btnResearcherDetailRoster).setOnClickListener(view ->
                startActivity(ResearcherRosterActivity.createIntent(this, studyId))
        );
        findViewById(R.id.btnResearcherDetailFeedback).setOnClickListener(view ->
                startActivity(ResearcherFeedbackActivity.createIntentForStudy(
                        this, studyId, nameField.getText().toString()
                ))
        );
    }

    private void loadStudy() {
        StudyApiClient.fetchStudy(this, httpClient, studyId, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(() -> showStudy(study));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> showError(
                        error.isEmpty() ? getString(R.string.researcher_detail_error) : error
                ));
            }
        });
    }

    private void showStudy(@NonNull Study study) {
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);

        currentStatus = study.getStatus();
        statusView.setText(currentStatus);
        nameField.setText(study.getName());
        durationField.setText(String.valueOf(study.getDuration()));
        descriptionField.setText(study.getDescription());

        configureLifecycleButton();
        content.setVisibility(View.VISIBLE);
    }

    private void configureLifecycleButton() {
        String action = StudyLifecycle.nextAction(currentStatus);
        if (action == null) {
            lifecycleButton.setVisibility(View.GONE);
            return;
        }

        lifecycleButton.setVisibility(View.VISIBLE);
        switch (action) {
            case "publish":
                lifecycleButton.setText(R.string.publish_study);
                break;
            case "activate":
                lifecycleButton.setText(R.string.activate_study);
                break;
            case "close":
                lifecycleButton.setText(R.string.close_study);
                break;
        }
        lifecycleButton.setOnClickListener(view -> runLifecycleAction(action));
    }

    private void runLifecycleAction(@NonNull String action) {
        lifecycleButton.setEnabled(false);
        StudyApiClient.LifecycleListener listener = new StudyApiClient.LifecycleListener() {
            @Override
            public void onUpdated(@NonNull Study study) {
                runOnUiThread(() -> {
                    lifecycleButton.setEnabled(true);
                    Toast.makeText(
                            ResearcherStudyDetailActivity.this,
                            R.string.study_updated,
                            Toast.LENGTH_SHORT
                    ).show();
                    showStudy(study);
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    lifecycleButton.setEnabled(true);
                    Toast.makeText(ResearcherStudyDetailActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        };

        switch (action) {
            case "publish":
                StudyApiClient.publishStudy(this, httpClient, studyId, listener);
                break;
            case "activate":
                StudyApiClient.activateStudy(this, httpClient, studyId, listener);
                break;
            case "close":
                StudyApiClient.closeStudy(this, httpClient, studyId, listener);
                break;
        }
    }

    private void saveChanges() {
        saveButton.setEnabled(false);
        String name = nameField.getText().toString();
        String durationText = durationField.getText().toString();
        String description = descriptionField.getText().toString();

        int duration;
        try {
            duration = Integer.parseInt(durationText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.researcher_field_duration, Toast.LENGTH_SHORT).show();
            saveButton.setEnabled(true);
            return;
        }

        saveField("name", name, () -> saveField("duration", duration, () -> saveField(
                "description",
                description,
                this::finishSave
        )));
    }

    private void saveField(@NonNull String column, @NonNull Object value, @NonNull Runnable onSuccess) {
        StudyApiClient.editStudy(this, httpClient, studyId, column, value, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(onSuccess);
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(ResearcherStudyDetailActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void finishSave() {
        saveButton.setEnabled(true);
        Toast.makeText(this, R.string.study_updated, Toast.LENGTH_SHORT).show();
        loadStudy();
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        messageView.setText(error);
        messageView.setVisibility(View.VISIBLE);
    }
}