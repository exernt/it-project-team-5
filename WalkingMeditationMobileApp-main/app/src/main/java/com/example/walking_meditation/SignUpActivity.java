package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.SessionManager;

import org.json.JSONObject;

import okhttp3.*;
import java.io.IOException;


public class SignUpActivity extends AppCompatActivity {

    private Button btnSignUp;
    private CheckBox privacyCheckbox;
    private AutoCompleteTextView emailField, passwordField,passwordConfirmField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        btnSignUp = findViewById(R.id.btnSignUpConfirm);
        emailField = findViewById(R.id.signUpEmail);
        passwordField = findViewById(R.id.signUpPassword);
        passwordConfirmField = findViewById(R.id.signUpConfirmPassword);
        privacyCheckbox = findViewById(R.id.signUpPrivacyCheckbox);
        findViewById(R.id.signUpPrivacyLink).setOnClickListener(view ->
                startActivity(new Intent(this, PrivacyPolicyActivity.class)));

        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String email = emailField.getText().toString();
                String password = passwordField.getText().toString();
                String passwordConfirm = passwordConfirmField.getText().toString();
                if (!email.isEmpty() && !password.isEmpty() && password.equals(passwordConfirm)) {
                    if (!privacyCheckbox.isChecked()) {
                        Toast.makeText(SignUpActivity.this,
                                R.string.privacy_policy_agree_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performSignUp(email, password);
                }
            }
        });
    }
    private void performSignUp(String email, String password) {
        OkHttpClient client = new OkHttpClient();

        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        final String jsonString;
        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            payload.put("password", password);
            jsonString = payload.toString();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to prepare registration", Toast.LENGTH_SHORT).show();
            return;
        }
        RequestBody body = RequestBody.create(jsonString, JSON);

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/register")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        SignUpActivity.this,
                        "Registration failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closedResponse = response) {
                    if (!closedResponse.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(
                                SignUpActivity.this,
                                "Registration failed (" + closedResponse.code() + ")",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }
                    loginAfterRegistration(client, email, password);
                }
            }
        });
    }

    private void loginAfterRegistration(OkHttpClient client, String email, String password) {
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
                public void onFailure(Call call, IOException e) {
                    showLoginAfterRegistrationError();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            showLoginAfterRegistrationError();
                            return;
                        }
                        try {
                            JSONObject tokens = new JSONObject(closedResponse.body().string());
                            SessionManager.saveTokens(
                                    SignUpActivity.this,
                                    tokens.getString("access_token"),
                                    tokens.getString("refresh_token")
                            );
                            runOnUiThread(() -> {
                                startActivity(new Intent(SignUpActivity.this, MainActivity.class));
                                finish();
                            });
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

    private void showLoginAfterRegistrationError() {
        runOnUiThread(() -> {
            Toast.makeText(
                    SignUpActivity.this,
                    "Account created. Please sign in to continue.",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(SignUpActivity.this, LoginActivity.class));
            finish();
        });
    }

}