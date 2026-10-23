package com.example.walking_meditation;

import android.app.Activity;
import android.content.Context;
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

public class ResearcherScriptParametersActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";
    private static final String EXTRA_SCRIPT_PARAMETERS = "script_parameters";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private EditText parametersField;
    private Button confirmButton;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String studyId,
            @NonNull String scriptParameters
    ) {
        return new Intent(context, ResearcherScriptParametersActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId)
                .putExtra(EXTRA_SCRIPT_PARAMETERS, scriptParameters);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_script_parameters);
        setTitle(R.string.script_parameters_title);

        parametersField = findViewById(R.id.scriptParametersText);
        confirmButton = findViewById(R.id.btnScriptParametersConfirm);

        String requestedStudyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }
        parametersField.setText(getIntent().getStringExtra(EXTRA_SCRIPT_PARAMETERS));

        findViewById(R.id.btnScriptParametersCancel).setOnClickListener(view -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        confirmButton.setOnClickListener(view -> saveParameters());

        if (studyId.isEmpty()) {
            Toast.makeText(this, R.string.researcher_detail_error, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void saveParameters() {
        confirmButton.setEnabled(false);
        StudyApiClient.editStudy(
                this,
                httpClient,
                studyId,
                "script_generation_parameters",
                parametersField.getText().toString(),
                new StudyApiClient.StudyListener() {
                    @Override
                    public void onLoaded(@NonNull Study study) {
                        runOnUiThread(() -> {
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            confirmButton.setEnabled(true);
                            Toast.makeText(
                                    ResearcherScriptParametersActivity.this,
                                    error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }
}
