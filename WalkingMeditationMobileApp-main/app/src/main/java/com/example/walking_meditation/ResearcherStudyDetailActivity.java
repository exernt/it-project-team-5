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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.ResponseExportApiClient;
import com.example.walking_meditation.utils.StudyApiClient;
import com.example.walking_meditation.utils.StudyLifecycle;

import okhttp3.OkHttpClient;

public class ResearcherStudyDetailActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ActivityResultLauncher<Intent> scriptParametersLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            loadStudy();
                        }
                    }
            );
    private String studyId = "";
    private String currentStatus = "";
    private String currentScriptParameters = "";

    private ProgressBar progress;
    private ScrollView content;
    private TextView statusView;
    private TextView messageView;
    private EditText nameField;
    private EditText durationField;
    private EditText descriptionField;
    private Button saveButton;
    private Button lifecycleButton;
    private Button exportButton;

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
        exportButton = findViewById(R.id.btnResearcherDetailExportResponses);

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
        findViewById(R.id.btnResearcherDetailQuestionnairesList).setOnClickListener(view -> {
            Intent intent = new Intent(this, ResearcherQuestionnaireListActivity.class);
            intent.putExtra("study_id", studyId);
            startActivity(intent);
        });
        findViewById(R.id.btnResearcherDetailFeedback).setOnClickListener(view ->
                startActivity(ResearcherFeedbackActivity.createIntentForStudy(
                        this, studyId, nameField.getText().toString()
                ))
        );
        exportButton.setOnClickListener(view -> requestStudyExport());
        findViewById(R.id.btnResearcherDetailShare).setOnClickListener(view -> shareInviteLink());
        findViewById(R.id.btnResearcherDetailScriptParameters).setOnClickListener(view ->
                scriptParametersLauncher.launch(ResearcherScriptParametersActivity.createIntent(
                        this,
                        studyId,
                        currentScriptParameters
                ))
        );
    }

    private void requestStudyExport() {
        setExportInProgress(true);
        ResponseExportApiClient.requestStudy(
                this,
                httpClient,
                studyId,
                new ResponseExportApiClient.ExportListener() {
                    @Override
                    public void onRequested(boolean cached) {
                        runOnUiThread(() -> {
                            setExportInProgress(false);
                            Toast.makeText(
                                    ResearcherStudyDetailActivity.this,
                                    cached ? R.string.export_ready_email_sent
                                            : R.string.export_requested,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            setExportInProgress(false);
                            Toast.makeText(
                                    ResearcherStudyDetailActivity.this,
                                    error.isEmpty()
                                            ? getString(R.string.export_request_error)
                                            : error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void setExportInProgress(boolean inProgress) {
        exportButton.setEnabled(!inProgress);
        exportButton.setText(inProgress
                ? R.string.requesting_export
                : R.string.export_study_responses);
    }

    private void shareInviteLink() {
        if (studyId.isEmpty()) {
            Toast.makeText(this, "Study ID not available", Toast.LENGTH_SHORT).show();
            return;
        }
        String inviteUrl = BuildConfig.SERVER_BASE_URL + "/study/join?id=" + studyId;
        String shareMessage = "Join my Walking Meditation study! Click this link to join:\n" + inviteUrl;

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareMessage);
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, "Share Study Invite Link");
        startActivity(shareIntent);
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
        currentScriptParameters = study.getScriptGenerationParameters();

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
