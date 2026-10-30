package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.audio.CurrentWalk;
import com.example.walking_meditation.utils.AuthenticatedHttpClient;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.ProfileInputValidator;
import com.example.walking_meditation.utils.RoutePlanFiles;
import com.example.walking_meditation.utils.SessionManager;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProfileActivity extends AppCompatActivity {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PROFILE_URL = BuildConfig.SERVER_BASE_URL + "/auth/me";

    private EditText ageField;
    private EditText otherGenderField;
    private RadioGroup genderGroup;
    private RadioButton maleButton;
    private RadioButton femaleButton;
    private RadioButton otherButton;
    private Button saveButton;
    private Button deleteButton;
    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        ageField = findViewById(R.id.profileAge);
        otherGenderField = findViewById(R.id.profileOtherGender);
        genderGroup = findViewById(R.id.profileGenderGroup);
        maleButton = findViewById(R.id.profileGenderMale);
        femaleButton = findViewById(R.id.profileGenderFemale);
        otherButton = findViewById(R.id.profileGenderOther);
        saveButton = findViewById(R.id.btnSaveProfile);
        deleteButton = findViewById(R.id.btnDeleteAccount);
        httpClient = AuthenticatedHttpClient.configure(this, new OkHttpClient.Builder()).build();

        genderGroup.setOnCheckedChangeListener((group, checkedId) ->
                otherGenderField.setVisibility(
                        checkedId == R.id.profileGenderOther ? View.VISIBLE : View.GONE
                ));
        saveButton.setOnClickListener(view -> saveProfile());
        deleteButton.setOnClickListener(view -> showDeleteConfirmation());

        setActionsEnabled(false);
        loadProfile();
    }

    private void loadProfile() {
        Request request = new Request.Builder().url(PROFILE_URL).get().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                showError("Unable to load profile");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response closedResponse = response) {
                    if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                        showError(closedResponse.code() == 401
                                ? "Your session has expired. Please sign in again."
                                : "Unable to load profile (" + closedResponse.code() + ")");
                        return;
                    }
                    JSONObject profile = new JSONObject(closedResponse.body().string());
                    Integer age = profile.isNull("age") ? null : profile.getInt("age");
                    String gender = profile.isNull("gender") ? "" : profile.getString("gender");
                    runOnUiThread(() -> populateProfile(age, gender));
                } catch (Exception error) {
                    showError("Unable to read profile");
                }
            }
        });
    }

    private void populateProfile(Integer age, @NonNull String gender) {
        if (isFinishing() || isDestroyed()) return;
        ageField.setText(age == null ? "" : String.valueOf(age));
        if ("Male".equalsIgnoreCase(gender)) {
            maleButton.setChecked(true);
        } else if ("Female".equalsIgnoreCase(gender)) {
            femaleButton.setChecked(true);
        } else if (!gender.isEmpty()) {
            otherButton.setChecked(true);
            otherGenderField.setText(gender);
        }
        setActionsEnabled(true);
    }

    private void saveProfile() {
        final int age;
        final String gender;
        try {
            age = ProfileInputValidator.requireValidAge(ageField.getText().toString());
            gender = ProfileInputValidator.requireGender(
                    maleButton.isChecked(),
                    femaleButton.isChecked(),
                    otherButton.isChecked(),
                    otherGenderField.getText().toString()
            );
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("age", age);
            payload.put("gender", gender);
            setActionsEnabled(false);
            Request request = new Request.Builder()
                    .url(PROFILE_URL)
                    .put(RequestBody.create(payload.toString(), JSON))
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException error) {
                    showError("Unable to save profile");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful()) {
                            showError("Unable to save profile (" + closedResponse.code() + ")");
                            return;
                        }
                        runOnUiThread(ProfileActivity.this::finish);
                    }
                }
            });
        } catch (Exception error) {
            showError("Unable to prepare profile update");
        }
    }

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_delete_title)
                .setMessage(R.string.profile_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> deleteAccount())
                .show();
    }

    private void deleteAccount() {
        setActionsEnabled(false);
        Request request = new Request.Builder().url(PROFILE_URL).delete().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                showError("Unable to delete account");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (!closedResponse.isSuccessful()) {
                        showError("Unable to delete account (" + closedResponse.code() + ")");
                        return;
                    }
                    clearLocalAccountData();
                    runOnUiThread(() -> {
                        Intent intent = new Intent(ProfileActivity.this, LoginActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    });
                }
            }
        });
    }

    private void clearLocalAccountData() {
        try {
            new CurrentWalk(getFilesDir()).clear();
        } catch (IOException ignored) {
            // The account is already deleted; continue removing all other local session data.
        }
        SessionManager.clearSession(this);
        RoutePlanFiles.clearAll(this);
        CacheFiles.clearSensitiveFiles(this);
    }

    private void showError(@NonNull String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_LONG).show();
            setActionsEnabled(true);
        });
    }

    private void setActionsEnabled(boolean enabled) {
        saveButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
    }
}
