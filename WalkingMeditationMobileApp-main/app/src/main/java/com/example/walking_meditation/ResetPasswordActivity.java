package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Toast;

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

public class ResetPasswordActivity extends AppCompatActivity {

    private Button btnResetPasswordConfirm;
    private AutoCompleteTextView passwordField, passwordConfirmField;
    private String resetToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        resetToken = getIntent().getStringExtra("reset_token");

        btnResetPasswordConfirm = findViewById(R.id.btnResetPasswordConfrim);

        passwordField = findViewById(R.id.newPassword);
        passwordConfirmField = findViewById(R.id.newPasswordConfirm);

        btnResetPasswordConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String password = passwordField.getText().toString();
                String passwordConfirm = passwordConfirmField.getText().toString();
                if (!password.isEmpty() && !passwordConfirm.isEmpty() && password.equals(passwordConfirm)) {
                    changePassword(password);
                } else {
                    Toast.makeText(ResetPasswordActivity.this, "Passwords do not match or are empty", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void changePassword(String password) {
        OkHttpClient client = new OkHttpClient();

        JSONObject json = new JSONObject();
        try {
            json.put("token", resetToken != null ? resetToken : "");
            json.put("new_password", password);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to prepare request", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/password-reset/confirm")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closedResponse = response) {
                    if (closedResponse.isSuccessful()) {
                        runOnUiThread(() -> {
                            Toast.makeText(ResetPasswordActivity.this, "Password Reset Successful! Please Login.", Toast.LENGTH_LONG).show();

                            Intent intent = new Intent(ResetPasswordActivity.this, LoginActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            finish();
                        });
                    } else {
                        runOnUiThread(() -> Toast.makeText(
                                ResetPasswordActivity.this,
                                "Password reset failed (" + closedResponse.code() + ")",
                                Toast.LENGTH_LONG
                        ).show());
                    }
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(
                        ResetPasswordActivity.this,
                        "Connection failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }
}