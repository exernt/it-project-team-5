package com.example.walking_meditation;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.QuestionnaireApiClient;
import com.example.walking_meditation.utils.QuestionnairePublishing;
import com.example.walking_meditation.utils.StudyApiClient;

import org.json.JSONObject;

import okhttp3.OkHttpClient;

public class ResearcherEditQuestionnaireActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private EditText titleField;
    private TextView statusView;
    private Button saveButton, publishButton;
    private String questionnaireId = "";
    private String studyId = "";
    private String studyStatus = "";
    private boolean questionnairePublished;
    private boolean hasUnsavedChanges;
    private boolean initializing = true;
    private JSONObject currentDefinition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_edit_questionnaire);

        titleField = findViewById(R.id.editQuestionnaireTitle);
        statusView = findViewById(R.id.labelStatus);
        saveButton = findViewById(R.id.btnEditQuestionnaireSave);
        publishButton = findViewById(R.id.btnEditQuestionnairePublish);

        findViewById(R.id.btnEditQuestionnaireBack).setOnClickListener(view -> finish());

        questionnaireId = getIntent().getStringExtra("questionnaire_id");
        if (questionnaireId == null) questionnaireId = "";
        studyId = getIntent().getStringExtra("study_id");
        if (studyId == null) studyId = "";
        String title = getIntent().getStringExtra("questionnaire_title");
        String status = getIntent().getStringExtra("questionnaire_status");
        String definitionStr = getIntent().getStringExtra("questionnaire_definition");

        if (title != null) titleField.setText(title);
        if (status != null) statusView.setText("Status: " + status.toUpperCase());

        try {
            currentDefinition = definitionStr != null ? new JSONObject(definitionStr) : new JSONObject();
        } catch (Exception e) {
            currentDefinition = new JSONObject();
        }

        boolean isDraft = "draft".equalsIgnoreCase(status);
        questionnairePublished = !isDraft;
        if (!isDraft) {
            titleField.setEnabled(false);
            saveButton.setEnabled(false);
            publishButton.setEnabled(false);
        }

        saveButton.setOnClickListener(view -> saveChanges());
        publishButton.setOnClickListener(view -> publishQuestionnaire());
        titleField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!initializing) {
                    hasUnsavedChanges = true;
                    updatePublishButton();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        initializing = false;
        updatePublishButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStudyStatus();
    }

    private void refreshStudyStatus() {
        if (studyId.isEmpty()) {
            updatePublishButton();
            return;
        }
        StudyApiClient.fetchStudy(this, httpClient, studyId, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(() -> {
                    studyStatus = study.getStatus();
                    updatePublishButton();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    studyStatus = "";
                    updatePublishButton();
                    Toast.makeText(ResearcherEditQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveChanges() {
        String title = titleField.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        saveButton.setEnabled(false);
        try {
            currentDefinition.put("title", title);

            QuestionnaireApiClient.editQuestionnaire(this, httpClient, questionnaireId, currentDefinition, new QuestionnaireApiClient.Listener() {
                @Override
                public void onSuccess(@NonNull String id) {
                    runOnUiThread(() -> {
                        saveButton.setEnabled(true);
                        Toast.makeText(ResearcherEditQuestionnaireActivity.this, "Questionnaire updated successfully!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onFailure(@NonNull String error) {
                    runOnUiThread(() -> {
                        saveButton.setEnabled(true);
                        Toast.makeText(ResearcherEditQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            saveButton.setEnabled(true);
            Toast.makeText(this, "Failed to update definition", Toast.LENGTH_SHORT).show();
        }
    }

    private void publishQuestionnaire() {
        publishButton.setEnabled(false);
        QuestionnaireApiClient.publishAndIssueStudyQuestionnaire(this, httpClient, studyId, questionnaireId, new QuestionnaireApiClient.Listener() {
            @Override
            public void onSuccess(@NonNull String id) {
                runOnUiThread(() -> {
                    Toast.makeText(ResearcherEditQuestionnaireActivity.this, "Questionnaire published and issued to the study!", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    updatePublishButton();
                    Toast.makeText(ResearcherEditQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updatePublishButton() {
        boolean enabled = QuestionnairePublishing.canPublish(
                studyStatus,
                questionnaireId,
                questionnairePublished,
                hasUnsavedChanges
        );
        publishButton.setEnabled(enabled);
        publishButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                enabled ? R.color.sky_blue_dark : R.color.disabled_grey
        )));
    }
}
