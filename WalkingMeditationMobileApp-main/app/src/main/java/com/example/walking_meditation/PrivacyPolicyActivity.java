package com.example.walking_meditation;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Shows the app's privacy policy. The text lives in strings.xml
 * (privacy_policy_placeholder) so the final policy can be dropped in without code changes.
 */
public class PrivacyPolicyActivity extends AppCompatActivity {
    /**
     * Version of the privacy policy text sent during registration. Keep this
     * synchronized with CURRENT_PRIVACY_POLICY_VERSION in the server.
     */
    public static final int POLICY_VERSION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);
        setTitle(R.string.privacy_policy_title);

        TextView versionView = findViewById(R.id.privacyPolicyVersion);
        versionView.setText(getString(R.string.privacy_policy_version, POLICY_VERSION));

        Button btnBack = findViewById(R.id.btnPrivacyPolicyBack);
        btnBack.setOnClickListener(view -> finish());
    }
}
