package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.UserApiClient;

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

    private AutoCompleteTextView emailField, passwordField, passwordConfirmField, institutionField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_sign_up);

        Button btnSignUpConfirm = findViewById(R.id.btnResearcherSignUpConfirm);
        emailField = findViewById(R.id.researcherSignUpEmail);
        passwordField = findViewById(R.id.researcherSignUpPassword);
        passwordConfirmField = findViewById(R.id.researcherSignUpConfirmPassword);
        institutionField = findViewById(R.id.researcherInstitutionField);

        btnSignUpConfirm.setOnClickListener(view -> {
            String email = emailField.getText().toString().trim();
            String password = passwordField.getText().toString().trim();
            String passwordConfirm = passwordConfirmField.getText().toString().trim();
            String institution = institutionField.getText().toString().trim();

            if (!email.isEmpty() && !password.isEmpty() && password.equals(passwordConfirm) && !institution.isEmpty()) {
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
                    loginAndRegisterResearcher(client, email, password, institution);
                }
            }
        });
    }

    private void loginAndRegisterResearcher(OkHttpClient client, String email, String password, String institution) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            payload.put("password", password);
            Request request = new Request.Builder()
                    .url(BuildConfig.SERVER_BASE_URL + "/auth/login")
                    .post(RequestBody.create(
                            payload.toString(),
                            MediaType.get("application/json; charset=utf-8")
                    ))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    showLoginAfterRegistrationError();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            showLoginAfterRegistrationError();
                            return;
                        }
                        try {
                            JSONObject tokens = new JSONObject(closedResponse.body().string());
                            String accessToken = tokens.getString("access_token");
                            String refreshToken = tokens.getString("refresh_token");
                            SessionManager.saveTokens(
                                    ResearcherSignUpActivity.this,
                                    accessToken,
                                    refreshToken
                            );
                            callRegisterResearcherEndpoint(client, accessToken, institution);
                        } catch (Exception e) {
                            showLoginAfterRegistrationError();
                        }
                    }
                }
            });
        } catch (Exception e) {
            showLoginAfterRegistrationError();
        }
    }

    private void callRegisterResearcherEndpoint(OkHttpClient client, String accessToken, String institution) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("institution", institution);

            Request request = new Request.Builder()
                    .url(BuildConfig.SERVER_BASE_URL + "/auth/register-researcher")
                    .header("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(
                            payload.toString(),
                            MediaType.get("application/json; charset=utf-8")
                    ))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    runOnUiThread(() -> Toast.makeText(
                            ResearcherSignUpActivity.this,
                            "Failed to register researcher profile",
                            Toast.LENGTH_LONG
                    ).show());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful()) {
                            runOnUiThread(() -> Toast.makeText(
                                    ResearcherSignUpActivity.this,
                                    "Researcher profile setup failed (" + closedResponse.code() + ")",
                                    Toast.LENGTH_LONG
                            ).show());
                            return;
                        }
                        fetchUserInfoAndVerifyResearcher(client);
                    }
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(
                    ResearcherSignUpActivity.this,
                    "Unable to submit researcher profile",
                    Toast.LENGTH_LONG
            ).show());
        }
    }

    private void fetchUserInfoAndVerifyResearcher(OkHttpClient client) {
        UserApiClient.fetchCurrentUser(this, client, new UserApiClient.CurrentUserListener() {
            @Override
            public void onLoaded(@NonNull String role, boolean researcherVerified) {
                SessionManager.saveUserInfo(ResearcherSignUpActivity.this, role, researcherVerified);
                if (SessionManager.isVerifiedResearcher(ResearcherSignUpActivity.this)) {
                    runOnUiThread(() -> {
                        Toast.makeText(ResearcherSignUpActivity.this, "Researcher account registered successfully!", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(ResearcherSignUpActivity.this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(ResearcherSignUpActivity.this, "Registered. Note: Researcher verification pending approval.", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(ResearcherSignUpActivity.this, ResearcherLoginActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    });
                }
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ResearcherSignUpActivity.this, "Registered. Please log in.", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(ResearcherSignUpActivity.this, ResearcherLoginActivity.class);
                    startActivity(intent);
                    finish();
                });
            }
        });
    }

    private void showLoginAfterRegistrationError() {
        runOnUiThread(() -> {
            Toast.makeText(
                    ResearcherSignUpActivity.this,
                    "Researcher account created. Please sign in.",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(ResearcherSignUpActivity.this, ResearcherLoginActivity.class));
            finish();
        });
    }
}
