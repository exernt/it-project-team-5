package com.example.walking_meditation;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
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
        String prompt;
        boolean required;
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
            JSONObject scales = definition.optJSONObject("scales");
            if (scales == null) scales = new JSONObject();
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
                qi.prompt = prompt;
                qi.required = q.optBoolean("required", false);

                if ("rating".equalsIgnoreCase(type)) {
                    RadioGroup rg = new RadioGroup(this);
                    rg.setOrientation(RadioGroup.HORIZONTAL);
                    JSONObject scale = scales.getJSONObject(q.getString("scale_ref"));
                    int min = scale.getInt("min");
                    int max = scale.getInt("max");
                    long pointCount = (long) max - min + 1;
                    if (pointCount != 4 && pointCount != 5) {
                        throw new IllegalArgumentException(
                                "The app only supports 4- or 5-point numeric scales"
                        );
                    }
                    for (int val = min; val <= max; val++) {
                        RadioButton rb = new RadioButton(this);
                        rb.setText(String.valueOf(val));
                        rb.setId(View.generateViewId());
                        rb.setTag(val);
                        rg.addView(rb);
                        if (val == max) break;
                    }
                    RadioButton firstRb = (RadioButton) rg.getChildAt(0);
                    if (firstRb != null) {
                        rg.check(firstRb.getId());
                    }
                    questionsContainer.addView(rg);
                    qi.inputView = rg;
                } else if ("single_choice".equalsIgnoreCase(type)) {
                    RadioGroup choices = new RadioGroup(this);
                    choices.setOrientation(RadioGroup.VERTICAL);
                    addSingleChoiceOptions(choices, q.getJSONArray("options"));
                    questionsContainer.addView(choices);
                    qi.inputView = choices;
                } else if ("multiple_choice".equalsIgnoreCase(type)) {
                    LinearLayout choices = new LinearLayout(this);
                    choices.setOrientation(LinearLayout.VERTICAL);
                    addMultipleChoiceOptions(choices, q.getJSONArray("options"));
                    questionsContainer.addView(choices);
                    qi.inputView = choices;
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

    private void addSingleChoiceOptions(
            @NonNull RadioGroup group,
            @NonNull JSONArray options
    ) throws Exception {
        for (int index = 0; index < options.length(); index++) {
            Object option = options.get(index);
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(optionLabel(option));
            button.setTag(optionValue(option));
            group.addView(button);
        }
    }

    private void addMultipleChoiceOptions(
            @NonNull LinearLayout container,
            @NonNull JSONArray options
    ) throws Exception {
        for (int index = 0; index < options.length(); index++) {
            Object option = options.get(index);
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(optionLabel(option));
            checkBox.setTag(optionValue(option));
            container.addView(checkBox);
        }
    }

    @NonNull
    private String optionLabel(@NonNull Object option) throws Exception {
        return option instanceof JSONObject
                ? ((JSONObject) option).getString("label")
                : String.valueOf(option);
    }

    @NonNull
    private Object optionValue(@NonNull Object option) throws Exception {
        if (!(option instanceof JSONObject)) return option;
        JSONObject structuredOption = (JSONObject) option;
        return structuredOption.has("value")
                ? structuredOption.get("value")
                : structuredOption.getString("label");
    }

    @NonNull
    private JSONObject choiceAnswer(@NonNull Object value) throws Exception {
        JSONObject answer = new JSONObject();
        answer.put("value", value);
        answer.put("detail", JSONObject.NULL);
        return answer;
    }

    private void submitAnswers() {
        submitButton.setEnabled(false);
        try {
            JSONObject answers = new JSONObject();
            for (QuestionInput qi : questionInputs) {
                if ("rating".equalsIgnoreCase(qi.type)) {
                    RadioGroup rg = (RadioGroup) qi.inputView;
                    int checkedId = rg.getCheckedRadioButtonId();
                    RadioButton checkedRb = rg.findViewById(checkedId);
                    if (checkedRb == null || !(checkedRb.getTag() instanceof Integer)) {
                        if (qi.required) {
                            throw new IllegalArgumentException(
                                    "Please select a value for “" + qi.prompt + "”"
                            );
                        }
                        continue;
                    }
                    answers.put(qi.id, (Integer) checkedRb.getTag());
                } else if ("single_choice".equalsIgnoreCase(qi.type)) {
                    RadioGroup choices = (RadioGroup) qi.inputView;
                    RadioButton selected = choices.findViewById(
                            choices.getCheckedRadioButtonId()
                    );
                    if (selected == null) {
                        if (qi.required) {
                            throw new IllegalArgumentException(
                                    "Please select an option for “" + qi.prompt + "”"
                            );
                        }
                        continue;
                    }
                    answers.put(qi.id, choiceAnswer(selected.getTag()));
                } else if ("multiple_choice".equalsIgnoreCase(qi.type)) {
                    LinearLayout choices = (LinearLayout) qi.inputView;
                    JSONArray selectedAnswers = new JSONArray();
                    for (int index = 0; index < choices.getChildCount(); index++) {
                        CheckBox option = (CheckBox) choices.getChildAt(index);
                        if (option.isChecked()) {
                            selectedAnswers.put(choiceAnswer(option.getTag()));
                        }
                    }
                    if (qi.required && selectedAnswers.length() == 0) {
                        throw new IllegalArgumentException(
                                "Please select at least one option for “" + qi.prompt + "”"
                        );
                    }
                    answers.put(qi.id, selectedAnswers);
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
        } catch (IllegalArgumentException error) {
            submitButton.setEnabled(true);
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            submitButton.setEnabled(true);
            Toast.makeText(this, "Failed to compile answers", Toast.LENGTH_SHORT).show();
        }
    }
}
