package com.example.walking_meditation;

import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Shows the app's privacy policy. The text lives in strings.xml
 * (privacy_policy_placeholder) so the final policy can be dropped in without code changes.
 */
public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);
        setTitle(R.string.privacy_policy_title);

        Button btnBack = findViewById(R.id.btnPrivacyPolicyBack);
        btnBack.setOnClickListener(view -> finish());
    }
}