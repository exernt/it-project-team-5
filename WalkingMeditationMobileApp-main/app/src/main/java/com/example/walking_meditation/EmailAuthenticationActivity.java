package com.example.walking_meditation;

import android.os.Bundle;
import android.widget.Button;
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

public class EmailAuthenticationActivity extends AppCompatActivity {

    private Button btnResend;
    private String userEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_authentication);

        userEmail = getIntent().getStringExtra("email");

        btnResend = findViewById(R.id.btnResendCode);

        if (btnResend != null) {
            btnResend.setOnClickListener(v -> resendResetRequest());
        }
        findViewById(R.id.btnBackToLogin).setOnClickListener(v -> finish());
    }

    private void resendResetRequest() {
        if (userEmail == null || userEmail.isEmpty()) {
            Toast.makeText(this, "Email not available", Toast.LENGTH_SHORT).show();
            return;
        }

        OkHttpClient client = new OkHttpClient();
        JSONObject json = new JSONObject();
        try {
            json.put("email", userEmail);
        } catch (Exception e) {
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/password-reset/request")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        EmailAuthenticationActivity.this,
                        R.string.password_reset_resend_failed,
                        Toast.LENGTH_SHORT
                ).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    int message = closedResponse.isSuccessful()
                            ? R.string.password_reset_resent
                            : R.string.password_reset_resend_failed;
                    runOnUiThread(() -> Toast.makeText(
                            EmailAuthenticationActivity.this,
                            message,
                            Toast.LENGTH_LONG
                    ).show());
                }
            }
        });
    }
}
