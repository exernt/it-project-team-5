package com.example.walking_meditation;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.QuestionnaireApiClient;

import org.json.JSONObject;

import okhttp3.OkHttpClient;

public class ResearcherEditQuestionnaireActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private EditText titleField;
    private TextView statusView;
    private Button saveButton, publishButton;
    private String questionnaireId = "";
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
        if (!isDraft) {
            titleField.setEnabled(false);
            saveButton.setEnabled(false);
            publishButton.setEnabled(false);
        }

        saveButton.setOnClickListener(view -> saveChanges());
        publishButton.setOnClickListener(view -> publishQuestionnaire());
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
        QuestionnaireApiClient.publishQuestionnaire(this, httpClient, questionnaireId, new QuestionnaireApiClient.Listener() {
            @Override
            public void onSuccess(@NonNull String id) {
                runOnUiThread(() -> {
                    Toast.makeText(ResearcherEditQuestionnaireActivity.this, "Questionnaire published successfully!", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    publishButton.setEnabled(true);
                    Toast.makeText(ResearcherEditQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
