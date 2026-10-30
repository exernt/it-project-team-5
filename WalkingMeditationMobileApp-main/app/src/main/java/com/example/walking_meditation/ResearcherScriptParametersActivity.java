package com.example.walking_meditation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.StudyApiClient;
import com.google.android.material.tabs.TabLayout;

import okhttp3.OkHttpClient;

public class ResearcherScriptParametersActivity extends AppCompatActivity {
    private static final String EXTRA_STUDY_ID = "study_id";
    private static final String EXTRA_CONTROL_SCRIPT_PARAMETERS = "control_script_parameters";
    private static final String EXTRA_EXPERIMENTAL_SCRIPT_PARAMETERS = "experimental_script_parameters";
    private static final String EXTRA_CONTROL_TTS_VOICE = "control_tts_voice";
    private static final String EXTRA_EXPERIMENTAL_TTS_VOICE = "experimental_tts_voice";

    private final OkHttpClient httpClient = new OkHttpClient();
    private String studyId = "";
    private String controlParameters = "";
    private String experimentalParameters = "";
    private String controlTtsVoice = "male";
    private String experimentalTtsVoice = "male";
    private boolean showingControl = true;
    private EditText parametersField;
    private RadioGroup voiceSelector;
    private Button confirmButton;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String studyId,
            @NonNull String controlScriptParameters,
            @NonNull String experimentalScriptParameters,
            @NonNull String controlTtsVoice,
            @NonNull String experimentalTtsVoice
    ) {
        return new Intent(context, ResearcherScriptParametersActivity.class)
                .putExtra(EXTRA_STUDY_ID, studyId)
                .putExtra(EXTRA_CONTROL_SCRIPT_PARAMETERS, controlScriptParameters)
                .putExtra(EXTRA_EXPERIMENTAL_SCRIPT_PARAMETERS, experimentalScriptParameters)
                .putExtra(EXTRA_CONTROL_TTS_VOICE, controlTtsVoice)
                .putExtra(EXTRA_EXPERIMENTAL_TTS_VOICE, experimentalTtsVoice);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_script_parameters);
        setTitle(R.string.script_parameters_title);

        parametersField = findViewById(R.id.scriptParametersText);
        voiceSelector = findViewById(R.id.scriptParametersVoiceSelector);
        confirmButton = findViewById(R.id.btnScriptParametersConfirm);
        TabLayout groupTabs = findViewById(R.id.scriptParametersGroupTabs);

        String requestedStudyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        if (requestedStudyId != null) {
            studyId = requestedStudyId;
        }
        controlParameters = intentExtraOrEmpty(EXTRA_CONTROL_SCRIPT_PARAMETERS);
        experimentalParameters = intentExtraOrEmpty(EXTRA_EXPERIMENTAL_SCRIPT_PARAMETERS);
        controlTtsVoice = normalizedVoiceExtra(EXTRA_CONTROL_TTS_VOICE);
        experimentalTtsVoice = normalizedVoiceExtra(EXTRA_EXPERIMENTAL_TTS_VOICE);
        showGroup(true);

        groupTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(@NonNull TabLayout.Tab tab) {
                showGroup(tab.getPosition() == 0);
            }

            @Override
            public void onTabUnselected(@NonNull TabLayout.Tab tab) {
                captureDisplayedSettings();
            }

            @Override
            public void onTabReselected(@NonNull TabLayout.Tab tab) {
                // The selected group's text is already displayed.
            }
        });

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

    @NonNull
    private String intentExtraOrEmpty(@NonNull String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    @NonNull
    private String normalizedVoiceExtra(@NonNull String key) {
        return "female".equals(intentExtraOrEmpty(key)) ? "female" : "male";
    }

    private void captureDisplayedSettings() {
        String voice = voiceSelector.getCheckedRadioButtonId() == R.id.scriptParametersFemaleVoice
                ? "female"
                : "male";
        if (showingControl) {
            controlParameters = parametersField.getText().toString();
            controlTtsVoice = voice;
        } else {
            experimentalParameters = parametersField.getText().toString();
            experimentalTtsVoice = voice;
        }
    }

    private void showGroup(boolean control) {
        showingControl = control;
        parametersField.setText(control ? controlParameters : experimentalParameters);
        String voice = control ? controlTtsVoice : experimentalTtsVoice;
        voiceSelector.check("female".equals(voice)
                ? R.id.scriptParametersFemaleVoice
                : R.id.scriptParametersMaleVoice);
        parametersField.setSelection(parametersField.length());
    }

    private void saveParameters() {
        captureDisplayedSettings();
        confirmButton.setEnabled(false);
        saveSetting(0);
    }

    private void saveSetting(int index) {
        String[] columns = {
                "control_script_generation_parameters",
                "control_tts_voice",
                "experimental_script_generation_parameters",
                "experimental_tts_voice"
        };
        String[] values = {
                controlParameters,
                controlTtsVoice,
                experimentalParameters,
                experimentalTtsVoice
        };
        if (index >= columns.length) {
            runOnUiThread(() -> {
                setResult(Activity.RESULT_OK);
                finish();
            });
            return;
        }
        StudyApiClient.editStudy(
                this,
                httpClient,
                studyId,
                columns[index],
                values[index],
                new StudyApiClient.StudyListener() {
                    @Override
                    public void onLoaded(@NonNull Study study) {
                        saveSetting(index + 1);
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
