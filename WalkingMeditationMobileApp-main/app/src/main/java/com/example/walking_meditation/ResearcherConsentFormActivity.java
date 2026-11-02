package com.example.walking_meditation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.StudyApiClient;

import okhttp3.OkHttpClient;

/**
 * Lets the owning researcher write the consent form participants agree to when joining.
 * Once a study is published the server locks the form, so the screen is read-only then.
 */
public class ResearcherConsentFormActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";
    private static final String EXTRA_EDITABLE = "editable";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private boolean editable;

    private ProgressBar progress;
    private ScrollView content;
    private TextView messageView;
    private TextView versionView;
    private TextView lockedNoticeView;
    private EditText bodyField;
    private Button saveButton;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String studyId,
            boolean editable
    ) {
        return new Intent(context, ResearcherConsentFormActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId)
                .putExtra(EXTRA_EDITABLE, editable);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_consent_form);
        setTitle(R.string.consent_editor_title);

        progress = findViewById(R.id.consentEditorProgress);
        content = findViewById(R.id.consentEditorContent);
        messageView = findViewById(R.id.consentEditorMessage);
        versionView = findViewById(R.id.consentEditorVersion);
        lockedNoticeView = findViewById(R.id.consentEditorLockedNotice);
        bodyField = findViewById(R.id.consentEditorBody);
        saveButton = findViewById(R.id.btnConsentEditorSave);

        findViewById(R.id.btnConsentEditorBack).setOnClickListener(view -> finish());
        saveButton.setOnClickListener(view -> saveConsentForm());

        Intent intent = getIntent();
        String requestedStudyId = intent.getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }
        editable = intent.getBooleanExtra(EXTRA_EDITABLE, false);

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
                runOnUiThread(() -> showConsentForm(bodyText, version));
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> showError(
                        error.isEmpty() ? getString(R.string.consent_details_error) : error
                ));
            }
        });
    }

    private void saveConsentForm() {
        String bodyText = bodyField.getText().toString().trim();
        if (bodyText.isEmpty()) {
            Toast.makeText(this, R.string.consent_editor_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        saveButton.setEnabled(false);
        StudyApiClient.updateConsentForm(this, httpClient, studyId, bodyText, new StudyApiClient.ConsentFormListener() {
            @Override
            public void onLoaded(@NonNull String savedText, int version) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(
                            ResearcherConsentFormActivity.this,
                            R.string.consent_editor_saved,
                            Toast.LENGTH_SHORT
                    ).show();
                    showConsentForm(savedText, version);
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(ResearcherConsentFormActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showConsentForm(@NonNull String bodyText, int version) {
        progress.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        bodyField.setText(bodyText);
        bodyField.setEnabled(editable);
        saveButton.setVisibility(editable ? View.VISIBLE : View.GONE);
        lockedNoticeView.setVisibility(editable ? View.GONE : View.VISIBLE);
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
