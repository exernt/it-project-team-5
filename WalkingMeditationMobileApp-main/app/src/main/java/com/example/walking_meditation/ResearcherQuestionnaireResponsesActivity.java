package com.example.walking_meditation;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.QuestionnaireApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

public class ResearcherQuestionnaireResponsesActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout container;
    private String studyId = "";
    private String studyQuestionnaireId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_questionnaire_responses);

        studyId = getIntent().getStringExtra("study_id");
        studyQuestionnaireId = getIntent().getStringExtra("study_questionnaire_id");
        if (studyId == null) studyId = "";
        if (studyQuestionnaireId == null) studyQuestionnaireId = "";

        progress = findViewById(R.id.respProgress);
        message = findViewById(R.id.respMessage);
        container = findViewById(R.id.respContainer);

        findViewById(R.id.btnRespBack).setOnClickListener(view -> finish());

        loadResponses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadResponses();
    }

    private void loadResponses() {
        if (studyId.isEmpty() || studyQuestionnaireId.isEmpty()) {
            progress.setVisibility(View.GONE);
            message.setText("Missing study or questionnaire reference.");
            message.setVisibility(View.VISIBLE);
            return;
        }

        QuestionnaireApiClient.getQuestionnaireResponses(this, httpClient, studyId, studyQuestionnaireId, new QuestionnaireApiClient.QuestionnaireResponsesListener() {
            @Override
            public void onLoaded(@NonNull JSONArray responses) {
                runOnUiThread(() -> showResponses(responses));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    message.setText(error.isEmpty() ? "Unable to load responses." : error);
                    message.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showResponses(@NonNull JSONArray responses) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (responses.length() == 0) {
            message.setText("No responses submitted yet.");
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        for (int i = 0; i < responses.length(); i++) {
            try {
                JSONObject r = responses.getJSONObject(i);
                String respondentId = r.optString("respondent_id", "Unknown Respondent");
                String submittedOn = r.optString("submitted_on", "");
                JSONObject answers = r.optJSONObject("answers");
                if (answers == null) answers = new JSONObject();

                TextView tv = new TextView(this);
                StringBuilder sb = new StringBuilder();
                sb.append("Response #").append(i + 1).append("\n");
                sb.append("Respondent: ").append(respondentId).append("\n");
                sb.append("Submitted: ").append(submittedOn).append("\n");
                sb.append("Answers:\n");

                java.util.Iterator<String> keys = answers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    sb.append("  - ").append(key).append(": ").append(answers.opt(key)).append("\n");
                }

                tv.setText(sb.toString().trim());
                tv.setTextSize(14);
                tv.setTextColor(getResources().getColor(android.R.color.black, getTheme()));
                tv.setPadding(16, 16, 16, 16);
                tv.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
                container.addView(tv, params);
            } catch (Exception ignored) {}
        }
    }
}
