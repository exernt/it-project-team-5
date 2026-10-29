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

public class ResearcherLoginActivity extends AppCompatActivity {

    private Button btnResearcherLogin;
    private AutoCompleteTextView emailField, passwordField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_login);

        btnResearcherLogin = findViewById(R.id.btnResearcherLogin);
        emailField = findViewById(R.id.researcherEmailField);
        passwordField = findViewById(R.id.researcherPasswordField);

        Button btnResearcherSignUpNav = findViewById(R.id.btnResearcherSignUpNav);
        btnResearcherSignUpNav.setOnClickListener(view -> {
            Intent intent = new Intent(ResearcherLoginActivity.this, ResearcherSignUpActivity.class);
            startActivity(intent);
        });

        btnResearcherLogin.setOnClickListener(view -> {
            String email = emailField.getText().toString().trim();
            String password = passwordField.getText().toString().trim();
            if (!email.isEmpty() && !password.isEmpty()) {
                performResearcherLogin(email, password);
            } else {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performResearcherLogin(String email, String password) {
        OkHttpClient client = new OkHttpClient();
        MediaType jsonMediaType = MediaType.get("application/json; charset=utf-8");

        JSONObject payload = new JSONObject();
        try {
            payload.put("email", email);
            payload.put("password", password);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to prepare login", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), jsonMediaType);

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/login")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        ResearcherLoginActivity.this,
                        "Login failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response closedResponse = response) {
                    String responseData = closedResponse.body() == null
                            ? ""
                            : closedResponse.body().string();

                    if (!closedResponse.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(
                                ResearcherLoginActivity.this,
                                "Researcher login failed (" + closedResponse.code() + ")",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }

                    try {
                        JSONObject tokens = new JSONObject(responseData);
                        SessionManager.saveTokens(
                                ResearcherLoginActivity.this,
                                tokens.getString("access_token"),
                                tokens.getString("refresh_token")
                        );
                        fetchUserInfoAndVerifyResearcher(client);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(
                                ResearcherLoginActivity.this,
                                "Invalid server response",
                                Toast.LENGTH_LONG
                        ).show());
                    }
                }
            }
        });
    }

    private void fetchUserInfoAndVerifyResearcher(OkHttpClient client) {
        UserApiClient.fetchCurrentUser(this, client, new UserApiClient.CurrentUserListener() {
            @Override
            public void onLoaded(@NonNull String role, boolean researcherVerified) {
                SessionManager.saveUserInfo(ResearcherLoginActivity.this, role, researcherVerified);
                if (SessionManager.isVerifiedResearcher(ResearcherLoginActivity.this)) {
                    runOnUiThread(() -> {
                        Toast.makeText(ResearcherLoginActivity.this, "Welcome, Researcher!", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(ResearcherLoginActivity.this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    });
                } else {
                    boolean awaitingApproval = "researcher".equals(role);
                    runOnUiThread(() -> {
                        Toast.makeText(
                                ResearcherLoginActivity.this,
                                awaitingApproval
                                        ? R.string.researcher_login_pending_approval
                                        : R.string.researcher_login_not_researcher,
                                Toast.LENGTH_LONG
                        ).show();
                        SessionManager.clearSession(ResearcherLoginActivity.this);
                    });
                }
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> Toast.makeText(
                        ResearcherLoginActivity.this,
                        "Verification failed: " + message,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }
}