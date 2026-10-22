package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;

import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.UserApiClient;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.RoutePlanFiles;

import org.json.JSONObject;

import okhttp3.*;
import java.io.IOException;


public class LoginActivity extends AppCompatActivity {

    Button btnLogin, btnSignUp, btnForgotPassword, btnResearcherLoginNav;
    AutoCompleteTextView emailField, passwordField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        RoutePlanFiles.cleanupExpired(this);
        CacheFiles.cleanupExpiredSensitiveFiles(this, RoutePlanFiles.RETENTION_MS);

        btnLogin = findViewById(R.id.btnLogin);
        btnSignUp = findViewById(R.id.btnSignUp);
        btnForgotPassword = findViewById(R.id.btnForgotPassword);

        emailField = findViewById(R.id.emailField);
        passwordField = findViewById(R.id.passwordField);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String email = emailField.getText().toString();
                String password = passwordField.getText().toString();
                if (!email.isEmpty() && !password.isEmpty()) {
                    performLogin(email, password);
                }
            }
        });

        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(LoginActivity.this, SignUpActivity.class);
                startActivity(intent);
            }
        });

        btnForgotPassword.setOnClickListener(view -> {
            Intent intent = new Intent(LoginActivity.this, ForgotPasswordActivity.class);
            startActivity(intent);
        });

        btnResearcherLoginNav = findViewById(R.id.btnResearcherLoginNav);
        btnResearcherLoginNav.setOnClickListener(view -> {
            Intent intent = new Intent(LoginActivity.this, ResearcherLoginActivity.class);
            startActivity(intent);
        });

        restoreSessionIfAvailable();
    }

    private void restoreSessionIfAvailable() {
        if (!SessionManager.hasSessionCredentials(this)) {
            return;
        }

        setRestoringSession(true);
        UserApiClient.fetchCurrentUser(this, new OkHttpClient(), new UserApiClient.CurrentUserListener() {
            @Override
            public void onLoaded(@NonNull String role, boolean researcherVerified) {
                SessionManager.saveUserInfo(LoginActivity.this, role, researcherVerified);
                goToMain();
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setRestoringSession(false);
                    Toast.makeText(
                            LoginActivity.this,
                            "Unable to restore session: " + message,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }

            @Override
            public void onSessionExpired() {
                clearLocalSession();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setRestoringSession(false);
                    Toast.makeText(
                            LoginActivity.this,
                            "Your session has expired. Please sign in again.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void setRestoringSession(boolean restoring) {
        btnLogin.setEnabled(!restoring);
        btnSignUp.setEnabled(!restoring);
        btnForgotPassword.setEnabled(!restoring);
        btnResearcherLoginNav.setEnabled(!restoring);
        emailField.setEnabled(!restoring);
        passwordField.setEnabled(!restoring);
        btnLogin.setText(restoring ? R.string.restoring_session : R.string.login);
    }

    private void clearLocalSession() {
        SessionManager.clearSession(this);
        RoutePlanFiles.clearAll(this);
        CacheFiles.clearSensitiveFiles(this);
    }
    private void performLogin(String email, String password) {
        OkHttpClient client = new OkHttpClient();

        MediaType jsonMediaType = MediaType.get("application/json; charset=utf-8");
        final String jsonString;
        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            payload.put("password", password);
            jsonString = payload.toString();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to prepare login", Toast.LENGTH_SHORT).show();
            return;
        }
        RequestBody body = RequestBody.create(jsonString, jsonMediaType);

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/login")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        LoginActivity.this,
                        "Login failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closedResponse = response) {
                    String responseData = closedResponse.body() == null
                            ? ""
                            : closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(
                                LoginActivity.this,
                                "Login failed (" + closedResponse.code() + ")",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }

                    try {
                        JSONObject tokens = new JSONObject(responseData);
                        SessionManager.saveTokens(
                                LoginActivity.this,
                                tokens.getString("access_token"),
                                tokens.getString("refresh_token")
                        );
                        fetchUserInfoThenContinue(client);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(
                                LoginActivity.this,
                                "Login response was invalid",
                                Toast.LENGTH_LONG
                        ).show());
                    }
                }
            }
        });
    }
    private void fetchUserInfoThenContinue(OkHttpClient client) {
        UserApiClient.fetchCurrentUser(this, client, new UserApiClient.CurrentUserListener() {
            @Override
            public void onLoaded(@NonNull String role, boolean researcherVerified) {
                SessionManager.saveUserInfo(LoginActivity.this, role, researcherVerified);
                goToMain();
            }

            @Override
            public void onFailure(@NonNull String message) {
                // Role couldn't be fetched — proceed anyway as a plain participant
                // rather than blocking login entirely on a secondary call.
                SessionManager.saveUserInfo(LoginActivity.this, "participant", false);
                goToMain();
            }
        });
    }

    private void goToMain() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }
}
