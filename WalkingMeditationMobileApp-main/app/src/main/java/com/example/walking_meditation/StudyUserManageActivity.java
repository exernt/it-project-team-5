package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_FROM_STUDY_MANAGE;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.models.StudyEnrollment;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.StudyApiClient;
import com.example.walking_meditation.utils.StudyConfigClient;
import com.example.walking_meditation.utils.StudyTime;

import java.time.Instant;

import okhttp3.OkHttpClient;

public class StudyUserManageActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressBar progress;
    private LinearLayout content;
    private TextView nameView;
    private TextView remainingView;
    private TextView descriptionView;
    private TextView messageView;
    private Button startButton;
    private Button leaveButton;
    private @Nullable StudyEnrollment currentEnrollment;
    private boolean loadingStudy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_user_manage);
        setTitle(R.string.study_user_manage_title);

        progress = findViewById(R.id.studyManageProgress);
        content = findViewById(R.id.studyManageContent);
        nameView = findViewById(R.id.currentStudyName);
        remainingView = findViewById(R.id.currentStudyRemaining);
        descriptionView = findViewById(R.id.currentStudyDescription);
        messageView = findViewById(R.id.studyManageMessage);
        startButton = findViewById(R.id.btnStartStudyMeditation);
        leaveButton = findViewById(R.id.btnLeaveStudy);

        startButton.setOnClickListener(view -> startStudyMeditation());
        leaveButton.setOnClickListener(view -> leaveStudy());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCurrentStudy();
    }

    private void loadCurrentStudy() {
        if (loadingStudy) {
            return;
        }
        loadingStudy = true;
        if (currentEnrollment == null) {
            progress.setVisibility(View.VISIBLE);
            content.setVisibility(View.GONE);
        }

        StudyApiClient.fetchCurrentEnrollment(
                this,
                httpClient,
                new StudyApiClient.CurrentStudyListener() {
                    @Override
                    public void onLoaded(@Nullable StudyEnrollment enrollment) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            loadingStudy = false;
                            if (enrollment == null) {
                                SessionManager.clearStudyConfiguration(
                                        StudyUserManageActivity.this
                                );
                                returnToMain();
                                return;
                            }
                            currentEnrollment = enrollment;
                            showCurrentStudy(enrollment);
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            loadingStudy = false;
                            if (currentEnrollment == null) {
                                showError(error);
                            } else {
                                Toast.makeText(
                                        StudyUserManageActivity.this,
                                        error,
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        });
                    }
                }
        );
    }

    private void showCurrentStudy(@NonNull StudyEnrollment enrollment) {
        Study study = enrollment.getStudy();
        long totalDays = StudyTime.remainingDays(
                enrollment.getRegisteredAt(),
                study.getDuration(),
                Instant.now()
        );
        int weeks = (int) (totalDays / 7);
        int days = (int) (totalDays % 7);
        String weekText = getResources().getQuantityString(
                R.plurals.study_remaining_weeks,
                weeks,
                weeks
        );
        String dayText = getResources().getQuantityString(
                R.plurals.study_remaining_days,
                days,
                days
        );

        nameView.setText(study.getName());
        remainingView.setText(getString(
                R.string.study_remaining_duration,
                weekText,
                dayText
        ));
        if (study.getDescription().isEmpty()) {
            descriptionView.setText(R.string.study_description_empty);
        } else {
            descriptionView.setText(study.getDescription());
        }
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    private void startStudyMeditation() {
        StudyEnrollment enrollment = currentEnrollment;
        if (enrollment == null) {
            return;
        }
        String studyId = enrollment.getStudy().getId();
        if (SessionManager.hasActiveStudy(this)
                && studyId.equals(SessionManager.getStudyId(this))) {
            openPathSelection();
            return;
        }

        startButton.setEnabled(false);
        startButton.setText(R.string.loading_study_settings);
        StudyConfigClient.fetchCurrent(this, httpClient, new StudyConfigClient.Listener() {
            @Override
            public void onLoaded(
                    boolean participating,
                    @NonNull String returnedStudyId,
                    @NonNull String parameters
            ) {
                runOnUiThread(() -> {
                    resetStartButton();
                    if (!participating || !studyId.equals(returnedStudyId)) {
                        SessionManager.clearStudyConfiguration(
                                StudyUserManageActivity.this
                        );
                        returnToMain();
                        return;
                    }
                    SessionManager.cacheStudyConfiguration(
                            StudyUserManageActivity.this,
                            returnedStudyId,
                            parameters
                    );
                    openPathSelection();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    resetStartButton();
                    Toast.makeText(
                            StudyUserManageActivity.this,
                            error,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void openPathSelection() {
        Intent intent = new Intent(this, PathSelectionActivity.class);
        intent.putExtra(EXTRA_FROM_STUDY_MANAGE, true);
        startActivity(intent);
    }

    private void leaveStudy() {
        StudyEnrollment enrollment = currentEnrollment;
        if (enrollment == null) {
            return;
        }
        leaveButton.setEnabled(false);
        leaveButton.setText(R.string.leaving_study);
        startButton.setEnabled(false);

        StudyApiClient.leaveStudy(
                this,
                httpClient,
                enrollment.getStudy().getId(),
                new StudyApiClient.LeaveListener() {
                    @Override
                    public void onLeft() {
                        runOnUiThread(() -> {
                            SessionManager.clearStudyConfiguration(
                                    StudyUserManageActivity.this
                            );
                            returnToMain();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            leaveButton.setEnabled(true);
                            leaveButton.setText(R.string.leave_study);
                            startButton.setEnabled(true);
                            Toast.makeText(
                                    StudyUserManageActivity.this,
                                    error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void resetStartButton() {
        startButton.setEnabled(true);
        startButton.setText(R.string.start_meditation);
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        messageView.setText(
                error.isEmpty() ? getString(R.string.current_study_error) : error
        );
        messageView.setVisibility(View.VISIBLE);
    }

    private void returnToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
}
