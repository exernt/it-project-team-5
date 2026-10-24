package com.example.walking_meditation;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.QuestionnaireApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

public class ResearcherCreateQuestionnaireActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private EditText titleField, promptField;
    private RadioGroup questionTypeGroup;
    private TextView questionCountText;
    private Button submitButton, addQuestionButton;

    private final JSONArray questionsArray = new JSONArray();
    private String studyId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_create_questionnaire);

        studyId = getIntent().getStringExtra("study_id");
        if (studyId == null) studyId = "";

        titleField = findViewById(R.id.createQuestionnaireTitle);
        promptField = findViewById(R.id.createQuestionnairePrompt);
        questionTypeGroup = findViewById(R.id.questionTypeGroup);
        questionCountText = findViewById(R.id.questionCountText);
        submitButton = findViewById(R.id.btnCreateQuestionnaireSubmit);
        addQuestionButton = findViewById(R.id.btnAddQuestion);

        findViewById(R.id.btnCreateQuestionnaireCancel).setOnClickListener(view -> finish());

        addQuestionButton.setOnClickListener(view -> addQuestionToList());
        submitButton.setOnClickListener(view -> submit());
    }

    private void addQuestionToList() {
        String prompt = promptField.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Please enter a question prompt", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isRating = questionTypeGroup.getCheckedRadioButtonId() == R.id.radioRating;

        try {
            JSONObject q = new JSONObject();
            int qIndex = questionsArray.length() + 1;
            q.put("id", "q" + qIndex);
            q.put("prompt", prompt);
            q.put("required", true);

            if (isRating) {
                q.put("type", "rating");
                q.put("scale_ref", "scale_1_5");
            } else {
                q.put("type", "text");
            }

            questionsArray.put(q);
            questionCountText.setText("Questions added: " + questionsArray.length());
            promptField.setText("");
            Toast.makeText(this, "Question added to list!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to add question", Toast.LENGTH_SHORT).show();
        }
    }

    private void submit() {
        String title = titleField.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a questionnaire title", Toast.LENGTH_SHORT).show();
            return;
        }

        if (questionsArray.length() == 0) {
            String prompt = promptField.getText().toString().trim();
            if (!prompt.isEmpty()) {
                addQuestionToList();
            } else {
                Toast.makeText(this, "Please add at least one question", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        submitButton.setEnabled(false);

        try {
            JSONObject definition = new JSONObject();
            definition.put("title", title);

            JSONObject scales = new JSONObject();
            JSONObject scale15 = new JSONObject();
            scale15.put("min", 1);
            scale15.put("max", 5);
            scales.put("scale_1_5", scale15);
            definition.put("scales", scales);

            definition.put("questions", questionsArray);

            QuestionnaireApiClient.Listener listener = new QuestionnaireApiClient.Listener() {
                @Override
                public void onSuccess(@NonNull String questionnaireId) {
                    runOnUiThread(() -> {
                        Toast.makeText(ResearcherCreateQuestionnaireActivity.this, "Questionnaire created and tied to study successfully!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onFailure(@NonNull String error) {
                    runOnUiThread(() -> {
                        submitButton.setEnabled(true);
                        Toast.makeText(ResearcherCreateQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }
            };

            if (!studyId.isEmpty()) {
                QuestionnaireApiClient.createAndIssueStudyQuestionnaire(this, httpClient, studyId, definition, listener);
            } else {
                QuestionnaireApiClient.createQuestionnaire(this, httpClient, definition, listener);
            }
        } catch (Exception e) {
            submitButton.setEnabled(true);
            Toast.makeText(this, "Failed to build questionnaire definition", Toast.LENGTH_SHORT).show();
        }
    }
}
