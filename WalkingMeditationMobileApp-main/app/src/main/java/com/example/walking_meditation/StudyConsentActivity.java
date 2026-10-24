package com.example.walking_meditation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.StudyApiClient;

import okhttp3.OkHttpClient;

public class StudyConsentActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";
    private static final String EXTRA_STUDY_NAME = "study_name";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private ProgressBar progress;
    private LinearLayout content;
    private TextView nameView;
    private TextView consentBodyView;
    private TextView versionView;
    private TextView messageView;
    private CheckBox agreeCheckbox;
    private Button joinButton;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String studyId,
            @NonNull String studyName
    ) {
        return new Intent(context, StudyConsentActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId)
                .putExtra(EXTRA_STUDY_NAME, studyName);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_consent);
        setTitle(R.string.consent_title);

        progress = findViewById(R.id.consentProgress);
        content = findViewById(R.id.consentContent);
        nameView = findViewById(R.id.consentStudyName);
        consentBodyView = findViewById(R.id.consentBody);
        versionView = findViewById(R.id.consentVersion);
        messageView = findViewById(R.id.consentMessage);
        agreeCheckbox = findViewById(R.id.consentAgreeCheckbox);
        joinButton = findViewById(R.id.btnConsentJoin);

        findViewById(R.id.btnConsentBack).setOnClickListener(view -> finish());
        joinButton.setOnClickListener(view -> attemptJoin());

        Intent intent = getIntent();
        String requestedStudyId = intent.getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }
        String studyName = intent.getStringExtra(EXTRA_STUDY_NAME);
        if (studyName != null) {
            nameView.setText(studyName);
        }

        if (studyId.isEmpty()) {
            showError(getString(R.string.consent_details_error));
            return;
        }
        loadConsentForm();
    }

    private void loadConsentForm() {
        StudyApiClient.fetchConsentForm(this, httpClient, studyId, new StudyApiClient.ConsentFormListener() {
            @Override
            public void onLoaded(@NonNull String bodyText, int version) {
                runOnUiThread(() -> showConsent(bodyText, version));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> showError(
                        error.isEmpty() ? getString(R.string.consent_details_error) : error
                ));
            }
        });
    }

    private void attemptJoin() {
        if (studyId.isEmpty()) {
            return;
        }
        if (!agreeCheckbox.isChecked()) {
            Toast.makeText(this, R.string.consent_agree_required, Toast.LENGTH_SHORT).show();
            return;
        }
        joinButton.setEnabled(false);
        joinButton.setText(R.string.joining_study);
        StudyApiClient.joinStudy(this, httpClient, studyId, true, new StudyApiClient.JoinListener() {
            @Override
            public void onJoined() {
                runOnUiThread(() -> {
                    Toast.makeText(StudyConsentActivity.this, "Successfully joined study!", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(StudyConsentActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    joinButton.setEnabled(true);
                    joinButton.setText(R.string.join_study);
                    Toast.makeText(StudyConsentActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showConsent(@NonNull String bodyText, int version) {
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        consentBodyView.setText(bodyText);
        versionView.setText(getString(R.string.consent_version, version));
        content.setVisibility(View.VISIBLE);
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        messageView.setText(error);
        messageView.setVisibility(View.VISIBLE);
    }
}