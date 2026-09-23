package com.example.walking_meditation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.StudyApiClient;

import java.util.List;

import okhttp3.OkHttpClient;

public class ResearcherRosterActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private ProgressBar progress;
    private ScrollView content;
    private TextView nameView;
    private TextView messageView;
    private LinearLayout controlContainer;
    private LinearLayout experimentalContainer;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String studyId) {
        return new Intent(context, ResearcherRosterActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_roster);
        setTitle(R.string.roster_title);

        progress = findViewById(R.id.rosterProgress);
        content = findViewById(R.id.rosterContent);
        nameView = findViewById(R.id.rosterStudyName);
        messageView = findViewById(R.id.rosterMessage);
        controlContainer = findViewById(R.id.rosterControlContainer);
        experimentalContainer = findViewById(R.id.rosterExperimentalContainer);

        findViewById(R.id.btnRosterBack).setOnClickListener(view -> finish());

        String requestedStudyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }

        if (studyId.isEmpty()) {
            showError(getString(R.string.roster_error));
            return;
        }
        loadRoster();
    }

    private void loadRoster() {
        StudyApiClient.fetchStudyStatus(this, httpClient, studyId, new StudyApiClient.StudyStatusListener() {
            @Override
            public void onLoaded(
                    @NonNull String name,
                    int duration,
                    @NonNull List<StudyApiClient.ParticipantInfo> control,
                    @NonNull List<StudyApiClient.ParticipantInfo> experimental
            ) {
                runOnUiThread(() -> showRoster(name, control, experimental));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> showError(
                        error.isEmpty() ? getString(R.string.roster_error) : error
                ));
            }
        });
    }

    private void showRoster(
            @NonNull String name,
            @NonNull List<StudyApiClient.ParticipantInfo> control,
            @NonNull List<StudyApiClient.ParticipantInfo> experimental
    ) {
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        nameView.setText(name);

        fillGroup(controlContainer, control, "experimental");
        fillGroup(experimentalContainer, experimental, "control");

        content.setVisibility(View.VISIBLE);
    }

    private void fillGroup(
            @NonNull LinearLayout container,
            @NonNull List<StudyApiClient.ParticipantInfo> participants,
            @NonNull String swapTargetGroup
    ) {
        container.removeAllViews();

        if (participants.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.roster_empty_group);
            empty.setTextColor(getColor(R.color.hint_grey));
            container.addView(empty);
            return;
        }

        int bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_8);
        for (StudyApiClient.ParticipantInfo participant : participants) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView info = new TextView(this);
            String demo = (participant.gender != null ? participant.gender : "")
                    + (participant.gender != null && participant.age != null ? ", " : "")
                    + (participant.age != null ? participant.age + "" : "");
            info.setText(demo.isEmpty() ? participant.userId : demo);
            info.setTextSize(14);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            );
            row.addView(info, infoParams);

            Button swapButton = new Button(this);
            swapButton.setText("experimental".equals(swapTargetGroup)
                    ? R.string.move_to_experimental
                    : R.string.move_to_control);
            swapButton.setAllCaps(false);
            swapButton.setTextSize(12);
            swapButton.setOnClickListener(view -> swapParticipant(participant.userId, swapTargetGroup));
            row.addView(swapButton);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = bottomMargin;
            container.addView(row, rowParams);
        }
    }

    private void swapParticipant(@NonNull String userId, @NonNull String targetGroup) {
        StudyApiClient.swapGroup(this, httpClient, studyId, userId, targetGroup, new StudyApiClient.SwapGroupListener() {
            @Override
            public void onSwapped() {
                runOnUiThread(ResearcherRosterActivity.this::loadRoster);
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> Toast.makeText(ResearcherRosterActivity.this, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        messageView.setText(error);
        messageView.setVisibility(View.VISIBLE);
    }
}