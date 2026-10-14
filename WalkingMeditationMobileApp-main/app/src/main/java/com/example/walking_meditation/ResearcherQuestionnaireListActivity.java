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
            QuestionnaireApiClient.listStudyQuestionnaires(this, httpClient, studyId, listener);
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
            Button btn = new Button(this);
            btn.setText(q.getTitle() + " — [" + q.getStatus().toUpperCase() + "]");
            btn.setAllCaps(false);
            btn.setTextSize(16);
            btn.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
            btn.setOnClickListener(view -> {
                Intent intent = new Intent(this, ResearcherQuestionnaireResponsesActivity.class);
                intent.putExtra("study_id", studyId);
                intent.putExtra("study_questionnaire_id", q.getId());
                startActivity(intent);
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
            container.addView(btn, params);
        }
    }
}
