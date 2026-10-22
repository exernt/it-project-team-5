package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ForgotPasswordActivity extends AppCompatActivity {
    private Button btnEmailConfirm;
    private AutoCompleteTextView forgotPasswordEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        btnEmailConfirm = findViewById(R.id.btnEmailConfirm);
        forgotPasswordEmail = findViewById(R.id.forgotPasswordEmail);

        btnEmailConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String email = forgotPasswordEmail.getText().toString().trim();
                if (!email.isEmpty()) {
                    checkValidEmail(email);
                } else {
                    forgotPasswordEmail.setError("Email is required");
                }
            }
        });
    }

    private void checkValidEmail(String email) {
        OkHttpClient client = new OkHttpClient();

        JSONObject json = new JSONObject();
        try {
            json.put("email", email);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json"));

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/password-reset/request")
                .post(body)
                .build();


        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(ForgotPasswordActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        Toast.makeText(ForgotPasswordActivity.this, "If registered, a reset code has been sent to your email", Toast.LENGTH_LONG).show();

                        Intent intent = new Intent(ForgotPasswordActivity.this, EmailAuthenticationActivity.class);
                        intent.putExtra("email", email);
                        startActivity(intent);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(ForgotPasswordActivity.this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

}
