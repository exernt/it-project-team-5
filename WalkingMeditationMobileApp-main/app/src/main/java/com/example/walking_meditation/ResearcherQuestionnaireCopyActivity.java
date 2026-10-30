package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.QuestionnaireItem;
import com.example.walking_meditation.utils.QuestionnaireApiClient;

import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

public class ResearcherQuestionnaireCopyActivity extends AppCompatActivity {
    public static final String EXTRA_QUESTIONNAIRE_DEFINITION = "questionnaire_definition";

    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_questionnaire_copy);

        progress = findViewById(R.id.questionnaireCopyProgress);
        message = findViewById(R.id.questionnaireCopyMessage);
        container = findViewById(R.id.questionnaireCopyContainer);

        findViewById(R.id.btnQuestionnaireCopyBack).setOnClickListener(view -> finish());
        loadQuestionnaires();
    }

    private void loadQuestionnaires() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        QuestionnaireApiClient.listQuestionnaires(
                this,
                httpClient,
                new QuestionnaireApiClient.QuestionnaireListListener() {
                    @Override
                    public void onLoaded(@NonNull List<QuestionnaireItem> questionnaires) {
                        runOnUiThread(() -> showQuestionnaires(questionnaires));
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> showError(error));
                    }
                }
        );
    }

    private void showQuestionnaires(@NonNull List<QuestionnaireItem> questionnaires) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (questionnaires.isEmpty()) {
            message.setText(R.string.copy_questionnaire_empty);
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        for (QuestionnaireItem questionnaire : questionnaires) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(getString(
                    R.string.questionnaire_definition_row_label,
                    questionnaire.getTitle(),
                    questionnaire.getStatus().toUpperCase(Locale.ROOT)
            ));
            button.setTextSize(16);
            button.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
            button.setOnClickListener(view -> confirmCopy(questionnaire));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
            container.addView(button, params);
        }
    }

    private void confirmCopy(@NonNull QuestionnaireItem questionnaire) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.questionnaire_copy_warning_title)
                .setMessage(R.string.questionnaire_copy_warning_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> returnDefinition(questionnaire))
                .show();
    }

    private void returnDefinition(@NonNull QuestionnaireItem questionnaire) {
        Intent result = new Intent().putExtra(
                EXTRA_QUESTIONNAIRE_DEFINITION,
                questionnaire.getDefinition().toString()
        );
        setResult(RESULT_OK, result);
        finish();
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        message.setText(
                error.isEmpty() ? getString(R.string.copy_questionnaire_load_error) : error
        );
        message.setVisibility(View.VISIBLE);
    }
}
