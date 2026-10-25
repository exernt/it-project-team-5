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

public class ParticipantQuestionnaireListActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout container;
    private String studyId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_participant_questionnaire_list);

        studyId = getIntent().getStringExtra("study_id");
        if (studyId == null) studyId = "";

        progress = findViewById(R.id.participantQListProgress);
        message = findViewById(R.id.participantQListMessage);
        container = findViewById(R.id.participantQListContainer);

        findViewById(R.id.btnParticipantQListBack).setOnClickListener(view -> finish());

        loadQuestionnaires();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadQuestionnaires();
    }

    private void loadQuestionnaires() {
        if (studyId.isEmpty()) {
            progress.setVisibility(View.GONE);
            message.setText("Study ID not available.");
            message.setVisibility(View.VISIBLE);
            return;
        }

        QuestionnaireApiClient.listParticipantQuestionnaires(this, httpClient, studyId, new QuestionnaireApiClient.QuestionnaireListListener() {
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
        });
    }

    private void showQuestionnaires(@NonNull List<QuestionnaireItem> questionnaires) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (questionnaires.isEmpty()) {
            message.setText("No questionnaires available for this study yet.");
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        for (QuestionnaireItem q : questionnaires) {
            Button btn = new Button(this);
            String label = q.getTitle() + " — [" + q.getStatus().toUpperCase() + "]";
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(16);
            btn.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));

            if ("completed".equalsIgnoreCase(q.getStatus())) {
                btn.setEnabled(false);
                btn.setText(q.getTitle() + " — [COMPLETED]");
            } else {
                btn.setOnClickListener(view -> {
                    Intent intent = new Intent(this, ParticipantTakeQuestionnaireActivity.class);
                    intent.putExtra("study_id", studyId);
                    intent.putExtra("study_questionnaire_id", q.getId());
                    startActivity(intent);
                });
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
            container.addView(btn, params);
        }
    }
}
