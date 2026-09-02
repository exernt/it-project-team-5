package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import okhttp3.*;
import java.io.IOException;


public class SignUpActivity extends AppCompatActivity {

    private Button btnSignUp;
    private AutoCompleteTextView emailField, passwordField,passwordConfirmField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);


        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String email = emailField.getText().toString();
                String password = passwordField.getText().toString();
                String passwordConfirm = passwordConfirmField.getText().toString();
                if (!email.isEmpty() && !password.isEmpty() && password.equals(passwordConfirm)) {
                    performSignUp(email, password);
                }
            }
        });
    }
    private void performSignUp(String email, String password) {
        OkHttpClient client = new OkHttpClient();

        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        String jsonString = "{\"email\":\"" + email + "\", \"password\":\"" + password + "\"}";
        RequestBody body = RequestBody.create(jsonString, JSON);

        Request request = new Request.Builder()
                .url("https://your-api-endpoint.com/login") // placeholder
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseData = response.body().string();

                    runOnUiThread(() -> {
                        Intent intent = new Intent(SignUpActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    });
                } else {
                    // Handle server error
                }
            }
        });
    }


}
