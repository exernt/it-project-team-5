package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.ResponseExportApiClient;
import com.example.walking_meditation.utils.StudyApiClient;

import java.util.List;

import okhttp3.OkHttpClient;

public class ResearcherStudyListActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout studyContainer;
    private Button exportButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_study_list);

        progress = findViewById(R.id.researcherStudyListProgress);
        message = findViewById(R.id.researcherStudyListMessage);
        studyContainer = findViewById(R.id.researcherStudyListContainer);
        exportButton = findViewById(R.id.btnExportAllResponses);

        findViewById(R.id.btnNewStudy).setOnClickListener(view -> startActivity(
                new Intent(this, ResearcherCreateStudyActivity.class)
        ));
        exportButton.setOnClickListener(view -> requestAllStudiesExport());

        loadOwnedStudies();
    }

    private void requestAllStudiesExport() {
        setExportInProgress(true);
        ResponseExportApiClient.requestAllStudies(
                this,
                httpClient,
                new ResponseExportApiClient.ExportListener() {
                    @Override
                    public void onRequested(boolean cached) {
                        runOnUiThread(() -> {
                            setExportInProgress(false);
                            Toast.makeText(
                                    ResearcherStudyListActivity.this,
                                    cached ? R.string.export_ready_email_sent
                                            : R.string.export_requested,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            setExportInProgress(false);
                            Toast.makeText(
                                    ResearcherStudyListActivity.this,
                                    error.isEmpty()
                                            ? getString(R.string.export_request_error)
                                            : error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void setExportInProgress(boolean inProgress) {
        exportButton.setEnabled(!inProgress);
        exportButton.setText(inProgress ? R.string.requesting_export : R.string.export_all_responses);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadOwnedStudies();
    }

    private void loadOwnedStudies() {
        StudyApiClient.fetchOwnedStudies(this, httpClient, new StudyApiClient.StudyListListener() {
            @Override
            public void onLoaded(@NonNull List<Study> studies) {
                runOnUiThread(() -> showStudies(studies));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    message.setText(error.isEmpty() ? getString(R.string.study_list_error) : error);
                    message.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showStudies(@NonNull List<Study> studies) {
        progress.setVisibility(View.GONE);
        studyContainer.removeAllViews();

        if (studies.isEmpty()) {
            message.setText(R.string.no_owned_studies);
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        int bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        for (Study study : studies) {
            Button studyButton = new Button(this);
            studyButton.setText(getString(
                    R.string.researcher_study_row_label,
                    study.getName(),
                    study.getStatus()
            ));
            studyButton.setAllCaps(false);
            studyButton.setTextSize(16);
            studyButton.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
            studyButton.setOnClickListener(view -> startActivity(
                    ResearcherStudyDetailActivity.createIntent(this, study.getId())
            ));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = bottomMargin;
            studyContainer.addView(studyButton, params);
        }
    }
}
