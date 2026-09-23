package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
    private AutoCompleteTextView passwordField,passwordConfirmField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        btnResetPasswordConfirm = findViewById(R.id.btnResetPasswordConfrim);

        passwordField = findViewById(R.id.newPassword);
        passwordConfirmField = findViewById(R.id.newPasswordConfirm);


        btnResetPasswordConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {;
                String password = passwordField.getText().toString();
                String passwordConfirm = passwordConfirmField.getText().toString();
                if (!password.isEmpty() && !passwordConfirm.isEmpty() && password.equals(passwordConfirm)) {
                    changePassword(password);
                }
            }
        });

    }
    private void changePassword(String password) {
        OkHttpClient client = new OkHttpClient();
        String jsonString = "{\"password\":\"" + password + "\"}";
        RequestBody body = RequestBody.create(jsonString, MediaType.get("application/json"));

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/auth/password-reset/confirm/")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        Toast.makeText(ResetPasswordActivity.this, "Password Reset Successful! Please Login.", Toast.LENGTH_LONG).show();

                        Intent intent = new Intent(ResetPasswordActivity.this, LoginActivity.class);
                        startActivity(intent);
                    });
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                Toast.makeText(ResetPasswordActivity.this, "Connection Failed", Toast.LENGTH_LONG).show();
            }
        });
    }


}