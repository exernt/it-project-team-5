package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.QuestionnaireItem;
import com.example.walking_meditation.utils.QuestionnaireApiClient;

import java.util.List;
import java.util.Collections;

import okhttp3.OkHttpClient;

public class ResearcherQuestionnaireListActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout container;
    private String studyId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_questionnaire_list);

        studyId = getIntent().getStringExtra("study_id");
        if (studyId == null) studyId = "";

        progress = findViewById(R.id.questionnaireListProgress);
        message = findViewById(R.id.questionnaireListMessage);
        container = findViewById(R.id.questionnaireListContainer);

        findViewById(R.id.btnNewQuestionnaire).setOnClickListener(view -> {
            Intent intent = new Intent(this, ResearcherCreateQuestionnaireActivity.class);
            if (!studyId.isEmpty()) {
                intent.putExtra("study_id", studyId);
            }
            startActivity(intent);
        });
        findViewById(R.id.btnQuestionnaireListBack).setOnClickListener(view -> finish());

        loadQuestionnaires();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadQuestionnaires();
    }

    private void loadQuestionnaires() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        QuestionnaireApiClient.QuestionnaireListListener listener = new QuestionnaireApiClient.QuestionnaireListListener() {
            @Override
            public void onLoaded(@NonNull List<QuestionnaireItem> questionnaires) {
                runOnUiThread(() -> showQuestionnaires(questionnaires));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    message.setText(error.isEmpty() ? "Unable to load questionnaires." : error);
                    message.setVisibility(View.VISIBLE);
                });
            }
        };

        if (!studyId.isEmpty()) {
            QuestionnaireApiClient.listQuestionnaires(
                    this,
                    httpClient,
                    new QuestionnaireApiClient.QuestionnaireListListener() {
                        @Override
                        public void onLoaded(@NonNull List<QuestionnaireItem> definitions) {
                            QuestionnaireApiClient.listStudyQuestionnaires(
                                    ResearcherQuestionnaireListActivity.this,
                                    httpClient,
                                    studyId,
                                    new QuestionnaireApiClient.QuestionnaireListListener() {
                                        @Override
                                        public void onLoaded(@NonNull List<QuestionnaireItem> issued) {
                                            runOnUiThread(() -> showStudyQuestionnaires(definitions, issued));
                                        }

                                        @Override
                                        public void onFailure(@NonNull String error) {
                                            runOnUiThread(() -> showStudyQuestionnaires(
                                                    definitions,
                                                    Collections.emptyList()
                                            ));
                                        }
                                    }
                            );
                        }

                        @Override
                        public void onFailure(@NonNull String error) {
                            listener.onFailure(error);
                        }
                    }
            );
        } else {
            QuestionnaireApiClient.listQuestionnaires(this, httpClient, listener);
        }
    }

    private void showQuestionnaires(@NonNull List<QuestionnaireItem> questionnaires) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (questionnaires.isEmpty()) {
            message.setText("No questionnaires tied to this study yet.");
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        for (QuestionnaireItem q : questionnaires) {
            addDefinitionButton(q);
        }
    }

    private void showStudyQuestionnaires(
            @NonNull List<QuestionnaireItem> definitions,
            @NonNull List<QuestionnaireItem> issued
    ) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        boolean hasDefinitions = false;
        for (QuestionnaireItem questionnaire : definitions) {
            if (!hasDefinitions) {
                addSectionHeader("Questionnaire definitions");
                hasDefinitions = true;
            }
            addDefinitionButton(questionnaire);
        }

        if (!issued.isEmpty()) {
            addSectionHeader("Issued questionnaires");
            for (QuestionnaireItem questionnaire : issued) {
                addIssuedQuestionnaireButton(questionnaire);
            }
        }

        if (!hasDefinitions && issued.isEmpty()) {
            message.setText("No questionnaire definitions or issued questionnaires yet.");
            message.setVisibility(View.VISIBLE);
        } else {
            message.setVisibility(View.GONE);
        }
    }

    private void addSectionHeader(@NonNull String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(18);
        header.setTextColor(getColor(R.color.black));
        header.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_12), 0,
                getResources().getDimensionPixelSize(R.dimen.space_12));
        container.addView(header);
    }

    private void addDefinitionButton(@NonNull QuestionnaireItem questionnaire) {
        Button button = createQuestionnaireButton(
                questionnaire.getTitle() + " — [" + questionnaire.getStatus().toUpperCase() + "]"
        );
        button.setOnClickListener(view -> {
            Intent intent = new Intent(this, ResearcherCreateQuestionnaireActivity.class);
            intent.putExtra("study_id", studyId);
            intent.putExtra("questionnaire_id", questionnaire.getId());
            intent.putExtra("questionnaire_title", questionnaire.getTitle());
            intent.putExtra("questionnaire_status", questionnaire.getStatus());
            intent.putExtra("questionnaire_definition", questionnaire.getDefinition().toString());
            startActivity(intent);
        });
        addQuestionnaireButton(button);
    }

    private void addIssuedQuestionnaireButton(@NonNull QuestionnaireItem questionnaire) {
        boolean revoked = "revoked".equals(questionnaire.getStatus());
        Button button = createQuestionnaireButton(
                questionnaire.getTitle() + (revoked ? " — [REVOKED]" : " — [ISSUED]")
        );
        button.setOnClickListener(view -> {
            Intent intent = new Intent(this, ResearcherQuestionnaireResponsesActivity.class);
            intent.putExtra("study_id", studyId);
            intent.putExtra("study_questionnaire_id", questionnaire.getId());
            intent.putExtra("questionnaire_revoked", revoked);
            startActivity(intent);
        });
        addQuestionnaireButton(button);
    }

    @NonNull
    private Button createQuestionnaireButton(@NonNull String label) {
            Button btn = new Button(this);
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(16);
            btn.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
            return btn;
    }

    private void addQuestionnaireButton(@NonNull Button button) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
            container.addView(button, params);
    }
}
