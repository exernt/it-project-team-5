package com.example.walking_meditation;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.StudyApiClient;

import java.util.List;

import okhttp3.OkHttpClient;

public class StudyViewListActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private TextView message;
    private LinearLayout studyContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_viewlist);

        progress = findViewById(R.id.studyListProgress);
        message = findViewById(R.id.studyListMessage);
        studyContainer = findViewById(R.id.studyListContainer);

        loadOpenStudies();
    }

    private void loadOpenStudies() {
        StudyApiClient.fetchOpenStudies(this, httpClient, new StudyApiClient.StudyListListener() {
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
            message.setText(R.string.no_open_studies);
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        int bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        for (Study study : studies) {
            Button studyButton = new Button(this);
            studyButton.setText(study.getName());
            studyButton.setAllCaps(false);
            studyButton.setTextSize(16);
            studyButton.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
            studyButton.setOnClickListener(view -> startActivity(
                    StudyDetailsActivity.createIntent(this, study)
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
