package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_STUDY_ID;
import static com.example.walking_meditation.nav.Extras.EXTRA_STUDY_NAME;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.FeedbackApiClient;

import okhttp3.OkHttpClient;

/**
 * Lets participants submit feedback for research purposes, either about the
 * app in general or, when launched from a study screen, about that study
 * specifically.
 */
public class FeedbackActivity extends AppCompatActivity {
    private final OkHttpClient httpClient = new OkHttpClient();

    private ProgressBar progress;
    private View scroll;
    private TextView contextView;
    private RadioGroup categoryGroup;
    private RatingBar ratingBar;
    private EditText commentField;
    private TextView messageView;
    private Button submitButton;

    private @Nullable String studyId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);
        setTitle(R.string.feedback_heading);

        progress = findViewById(R.id.feedbackProgress);
        scroll = findViewById(R.id.feedbackScroll);
        contextView = findViewById(R.id.feedbackContext);
        categoryGroup = findViewById(R.id.feedbackCategoryGroup);
        ratingBar = findViewById(R.id.feedbackRating);
        commentField = findViewById(R.id.feedbackComment);
        messageView = findViewById(R.id.feedbackMessage);
        submitButton = findViewById(R.id.btnSubmitFeedback);

        studyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        String studyName = getIntent().getStringExtra(EXTRA_STUDY_NAME);

        if (studyId != null && !studyId.isEmpty()) {
            categoryGroup.check(R.id.categoryStudy);
            contextView.setText(
                    studyName == null || studyName.isEmpty()
                            ? getString(R.string.feedback_context_study_unnamed)
                            : getString(R.string.feedback_context_study, studyName)
            );
        } else {
            contextView.setText(R.string.feedback_context_general);
        }

        submitButton.setOnClickListener(view -> submitFeedback());
    }

    private void submitFeedback() {
        String category = selectedCategory();
        int ratingValue = (int) ratingBar.getRating();
        String comment = commentField.getText() == null
                ? ""
                : commentField.getText().toString().trim();

        if (ratingValue == 0) {
            showMessage(getString(R.string.feedback_missing_input));
            return;
        }

        hideMessage();
        submitButton.setEnabled(false);
        submitButton.setText(R.string.feedback_submitting);

        FeedbackApiClient.submitFeedback(
                this,
                httpClient,
                category.equals("study") ? studyId : null,
                category,
                ratingValue,
                comment.isEmpty() ? null : comment,
                new FeedbackApiClient.SubmitListener() {
                    @Override
                    public void onSubmitted() {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(
                                    FeedbackActivity.this,
                                    R.string.feedback_thanks,
                                    Toast.LENGTH_LONG
                            ).show();
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

    @NonNull
    private String selectedCategory() {
        int checkedId = categoryGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.categoryStudy) {
            return "study";
        }
        if (checkedId == R.id.categoryTechnical) {
            return "technical";
        }
        return "general";
    }

    private void resetSubmitButton() {
        submitButton.setEnabled(true);
        submitButton.setText(R.string.feedback_submit);
    }

    private void showMessage(@NonNull String message) {
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        messageView.setVisibility(View.GONE);
    }
}