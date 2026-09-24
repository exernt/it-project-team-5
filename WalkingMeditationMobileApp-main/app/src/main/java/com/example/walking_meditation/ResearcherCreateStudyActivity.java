package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.StudyApiClient;

import okhttp3.OkHttpClient;

public class ResearcherCreateStudyActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();
    private EditText nameField;
    private EditText durationField;
    private Button submitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_create_study);

        nameField = findViewById(R.id.createStudyName);
        durationField = findViewById(R.id.createStudyDuration);
        submitButton = findViewById(R.id.btnCreateStudySubmit);

        findViewById(R.id.btnCreateStudyCancel).setOnClickListener(view -> finish());
        submitButton.setOnClickListener(view -> submit());
    }

    private void submit() {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.create_study_name_error, Toast.LENGTH_SHORT).show();
            return;
        }

        int duration;
        try {
            duration = Integer.parseInt(durationField.getText().toString().trim());
            if (duration <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.create_study_duration_error, Toast.LENGTH_SHORT).show();
            return;
        }

        submitButton.setEnabled(false);
        StudyApiClient.createStudy(this, httpClient, name, duration, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(() -> {
                    startActivity(ResearcherStudyDetailActivity.createIntent(
                            ResearcherCreateStudyActivity.this,
                            study.getId()
                    ));
                    finish();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    Toast.makeText(ResearcherCreateStudyActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}