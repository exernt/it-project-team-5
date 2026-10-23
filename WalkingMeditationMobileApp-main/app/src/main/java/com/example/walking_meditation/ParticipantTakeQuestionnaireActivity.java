package com.example.walking_meditation;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.QuestionnaireApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

public class ParticipantTakeQuestionnaireActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView titleView;
    private LinearLayout questionsContainer;
    private Button submitButton;

    private String studyId = "";
    private String studyQuestionnaireId = "";
    private final List<QuestionInput> questionInputs = new ArrayList<>();

    private static class QuestionInput {
        String id;
        String type;
        View inputView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_participant_take_questionnaire);

        studyId = getIntent().getStringExtra("study_id");
        studyQuestionnaireId = getIntent().getStringExtra("study_questionnaire_id");
        if (studyId == null) studyId = "";
        if (studyQuestionnaireId == null) studyQuestionnaireId = "";

        progress = findViewById(R.id.takeQProgress);
        titleView = findViewById(R.id.takeQTitle);
        questionsContainer = findViewById(R.id.takeQQuestionsContainer);
        submitButton = findViewById(R.id.btnSubmitAnswers);

        findViewById(R.id.btnTakeQCancel).setOnClickListener(view -> finish());
        submitButton.setOnClickListener(view -> submitAnswers());

        loadQuestionnaireDetail();
    }

    private void loadQuestionnaireDetail() {
        QuestionnaireApiClient.getParticipantQuestionnaireDetail(this, httpClient, studyId, studyQuestionnaireId, new QuestionnaireApiClient.ParticipantDetailListener() {
            @Override
            public void onLoaded(@NonNull String title, @NonNull JSONObject definition) {
                runOnUiThread(() -> renderQuestionnaire(title, definition));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(ParticipantTakeQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void renderQuestionnaire(@NonNull String title, @NonNull JSONObject definition) {
        progress.setVisibility(View.GONE);
        titleView.setText(title);
        questionsContainer.removeAllViews();
        questionInputs.clear();

        try {
            JSONArray questions = definition.getJSONArray("questions");
            for (int i = 0; i < questions.length(); i++) {
                JSONObject q = questions.getJSONObject(i);
                String id = q.getString("id");
                String prompt = q.getString("prompt");
                String type = q.optString("type", "text");

                TextView promptTv = new TextView(this);
                promptTv.setText((i + 1) + ". " + prompt);
                promptTv.setTextSize(16);
                promptTv.setTextColor(getResources().getColor(android.R.color.black, getTheme()));
                promptTv.setPadding(0, 16, 0, 8);
                questionsContainer.addView(promptTv);

                QuestionInput qi = new QuestionInput();
                qi.id = id;
                qi.type = type;

                if ("rating".equalsIgnoreCase(type)) {
                    RadioGroup rg = new RadioGroup(this);
                    rg.setOrientation(RadioGroup.HORIZONTAL);
                    for (int val = 1; val <= 5; val++) {
                        android.widget.RadioButton rb = new android.widget.RadioButton(this);
                        rb.setText(String.valueOf(val));
                        rb.setId(View.generateViewId());
                        rb.setTag(val);
                        rg.addView(rb);
                    }
                    android.widget.RadioButton firstRb = (android.widget.RadioButton) rg.getChildAt(0);
                    if (firstRb != null) {
                        rg.check(firstRb.getId());
                    }
                    questionsContainer.addView(rg);
                    qi.inputView = rg;
                } else {
                    EditText et = new EditText(this);
                    et.setHint("Enter your response");
                    et.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
                    questionsContainer.addView(et);
                    qi.inputView = et;
                }

                questionInputs.add(qi);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to parse questions", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitAnswers() {
        submitButton.setEnabled(false);
        try {
            JSONObject answers = new JSONObject();
            for (QuestionInput qi : questionInputs) {
                if ("rating".equalsIgnoreCase(qi.type)) {
                    RadioGroup rg = (RadioGroup) qi.inputView;
                    int checkedId = rg.getCheckedRadioButtonId();
                    android.widget.RadioButton checkedRb = rg.findViewById(checkedId);
                    int ratingVal = 1;
                    if (checkedRb != null && checkedRb.getTag() instanceof Integer) {
                        ratingVal = (Integer) checkedRb.getTag();
                    }
                    answers.put(qi.id, ratingVal);
                } else {
                    EditText et = (EditText) qi.inputView;
                    String val = et.getText().toString().trim();
                    answers.put(qi.id, val);
                }
            }

            QuestionnaireApiClient.submitAnswers(this, httpClient, studyId, studyQuestionnaireId, answers, new QuestionnaireApiClient.Listener() {
                @Override
                public void onSuccess(@NonNull String questionnaireId) {
                    runOnUiThread(() -> {
                        Toast.makeText(ParticipantTakeQuestionnaireActivity.this, "Answers submitted successfully!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onFailure(@NonNull String error) {
                    runOnUiThread(() -> {
                        submitButton.setEnabled(true);
                        Toast.makeText(ParticipantTakeQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            submitButton.setEnabled(true);
            Toast.makeText(this, "Failed to compile answers", Toast.LENGTH_SHORT).show();
        }
    }
}
