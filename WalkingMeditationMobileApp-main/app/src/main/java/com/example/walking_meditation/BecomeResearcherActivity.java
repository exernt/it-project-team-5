package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.UserApiClient;

import okhttp3.OkHttpClient;

/**
 * Lets a participant request researcher access. Submitting immediately
 * flips their role to researcher server-side, but they stay unverified
 * (and so can't use the researcher dashboard) until an admin approves
 * them from the Admin Dashboard.
 */
public class BecomeResearcherActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();

    private EditText institutionField;
    private TextView messageView;
    private Button submitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_become_researcher);
        setTitle(R.string.become_researcher_heading);

        institutionField = findViewById(R.id.becomeResearcherInstitution);
        messageView = findViewById(R.id.becomeResearcherMessage);
        submitButton = findViewById(R.id.btnSubmitBecomeResearcher);

        submitButton.setOnClickListener(view -> submit());
    }

    private void submit() {
        String institution = institutionField.getText() == null
                ? ""
                : institutionField.getText().toString().trim();

        hideMessage();
        submitButton.setEnabled(false);
        submitButton.setText(R.string.become_researcher_submitting);

        UserApiClient.registerAsResearcher(
                this,
                httpClient,
                institution.isEmpty() ? null : institution,
                new UserApiClient.RegisterResearcherListener() {
                    @Override
                    public void onRegistered() {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(
                                    BecomeResearcherActivity.this,
                                    R.string.become_researcher_success,
                                    Toast.LENGTH_LONG
                            ).show();
                            startActivity(new Intent(BecomeResearcherActivity.this, MainActivity.class));
                            finish();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String message) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            resetSubmitButton();
                            showMessage(message);
                        });
                    }
                }
        );
    }

    private void resetSubmitButton() {
        submitButton.setEnabled(true);
        submitButton.setText(R.string.become_researcher_submit);
    }

    private void showMessage(@NonNull String message) {
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        messageView.setVisibility(View.GONE);
    }
}