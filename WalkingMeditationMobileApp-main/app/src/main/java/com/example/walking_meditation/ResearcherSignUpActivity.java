package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ResearcherSignUpActivity extends AppCompatActivity {
    private static final int MIN_PASSWORD_LENGTH = 8;

    private AutoCompleteTextView emailField, passwordField, passwordConfirmField, institutionField;
    private CheckBox privacyCheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_sign_up);

        Button btnSignUpConfirm = findViewById(R.id.btnResearcherSignUpConfirm);
        emailField = findViewById(R.id.researcherSignUpEmail);
        passwordField = findViewById(R.id.researcherSignUpPassword);
        passwordConfirmField = findViewById(R.id.researcherSignUpConfirmPassword);
        institutionField = findViewById(R.id.researcherInstitutionField);
        privacyCheckbox = findViewById(R.id.researcherSignUpPrivacyCheckbox);
        findViewById(R.id.researcherSignUpPrivacyLink).setOnClickListener(view ->
                startActivity(new Intent(this, PrivacyPolicyActivity.class)));

        btnSignUpConfirm.setOnClickListener(view -> {
            String email = emailField.getText().toString().trim();
            String password = passwordField.getText().toString().trim();
            String passwordConfirm = passwordConfirmField.getText().toString().trim();
            String institution = institutionField.getText().toString().trim();

            if (!email.isEmpty() && !password.isEmpty() && password.equals(passwordConfirm) && !institution.isEmpty()) {
                if (!privacyCheckbox.isChecked()) {
                    Toast.makeText(this, R.string.privacy_policy_agree_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (password.length() < MIN_PASSWORD_LENGTH) {
                    Toast.makeText(this, R.string.researcher_sign_up_password_too_short, Toast.LENGTH_SHORT).show();
                    return;
                }
                performResearcherSignUp(email, password, institution);
            } else {
                Toast.makeText(this, "Please fill all fields (passwords must match)", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performResearcherSignUp(String email, String password, String institution) {
        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        JSONObject payload = new JSONObject();
        try {
            payload.put("email", email);
            payload.put("password", password);
            payload.put("researcher_institution", institution);
            payload.put("agrees_to_privacy_policy", privacyCheckbox.isChecked());
            payload.put("privacy_policy_version", PrivacyPolicyActivity.POLICY_VERSION);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to prepare registration", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), JSON);

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/register")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        ResearcherSignUpActivity.this,
                        "Registration failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response closedResponse = response) {
                    if (!closedResponse.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(
                                ResearcherSignUpActivity.this,
                                "Researcher registration failed (" + closedResponse.code() + ")",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }
                    runOnUiThread(() -> {
                        Toast.makeText(
                                ResearcherSignUpActivity.this,
                                R.string.researcher_sign_up_success,
                                Toast.LENGTH_LONG
                        ).show();
                        startActivity(new Intent(
                                ResearcherSignUpActivity.this,
                                ResearcherLoginActivity.class
                        ));
                        finish();
                    });
                }
            }
        });
    }
}
