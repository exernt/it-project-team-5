package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
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

public class EmailAuthenticationActivity extends AppCompatActivity {

    private AutoCompleteTextView codeInput;
    private Button btnVerify, btnResend;
    private String userEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_authentication);

        userEmail = getIntent().getStringExtra("email");

        codeInput = findViewById(R.id.codeConfirm);
        btnVerify = findViewById(R.id.btnVerify);
        btnResend = findViewById(R.id.btnResendCode);

        btnVerify.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (!code.isEmpty()) {
                verifyCode(code);
            } else {
                codeInput.setError("Enter verification code");
            }
        });

    }

    private String generatedCode;

    private String generateVerificationCode() {
        java.util.Random random = new java.util.Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    private void sendCode(String targetEmail) {
        generatedCode = generateVerificationCode();

        String subject = "Your Verification Code";
        String message = "Hello,\n\nYour walking meditation verification code is: " + generatedCode;

        android.net.Uri uri = android.net.Uri.parse("mailto:" + targetEmail);
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, message);

        try {
            startActivity(Intent.createChooser(intent, "Send verification email via:"));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No email apps installed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void verifyCode(String code) {
        if (generatedCode != null && code.equals(generatedCode)) {
            Intent intent = new Intent(this, ResetPasswordActivity.class);
            intent.putExtra("email", userEmail);
            intent.putExtra("reset_token", code);
            startActivity(intent);
            finish();
        } else {
            codeInput.setError("Incorrect verification code");
        }
    }
}