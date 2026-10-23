package com.example.walking_meditation;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class PasswordResetLinkActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri data = getIntent().getData();
        String token = PasswordResetLinkParser.extractToken(
                data == null ? null : data.toString(),
                BuildConfig.APP_LINK_HOST
        );
        if (token == null) {
            Toast.makeText(this, R.string.password_reset_link_invalid, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ForgotPasswordActivity.class));
            finish();
            return;
        }

        startActivity(ResetPasswordActivity.createIntent(this, token));
        finish();
    }
}
