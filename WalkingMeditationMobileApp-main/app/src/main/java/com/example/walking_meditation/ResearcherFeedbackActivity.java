package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_STUDY_ID;
import static com.example.walking_meditation.nav.Extras.EXTRA_STUDY_NAME;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.FeedbackApiClient;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

/**
 * Lets researchers review feedback: either everything tied to one study
 * (when launched with EXTRA_STUDY_ID) or all general, non-study feedback.
 */
public class ResearcherFeedbackActivity extends AppCompatActivity {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
                    .withZone(ZoneId.systemDefault());

    private final OkHttpClient httpClient = new OkHttpClient();

    private ProgressBar progress;
    private TextView title;
    private TextView message;
    private LinearLayout container;
    private @Nullable String studyId;

    @NonNull
    public static Intent createIntentForStudy(
            @NonNull Context context,
            @NonNull String studyId,
            @NonNull String studyName
    ) {
        Intent intent = new Intent(context, ResearcherFeedbackActivity.class);
        intent.putExtra(EXTRA_STUDY_ID, studyId);
        intent.putExtra(EXTRA_STUDY_NAME, studyName);
        return intent;
    }

    @NonNull
    public static Intent createIntentForGeneralFeedback(@NonNull Context context) {
        return new Intent(context, ResearcherFeedbackActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_feedback);

        progress = findViewById(R.id.researcherFeedbackProgress);
        title = findViewById(R.id.researcherFeedbackTitle);
        message = findViewById(R.id.researcherFeedbackMessage);
        container = findViewById(R.id.researcherFeedbackContainer);

        findViewById(R.id.btnResearcherFeedbackBack).setOnClickListener(view -> finish());

        studyId = getIntent().getStringExtra(EXTRA_STUDY_ID);
        String studyName = getIntent().getStringExtra(EXTRA_STUDY_NAME);

        if (studyId != null && !studyId.isEmpty()) {
            title.setText(studyName == null || studyName.isEmpty()
                    ? getString(R.string.feedback_heading)
                    : getString(R.string.feedback_context_study, studyName));
            loadStudyFeedback(studyId);
        } else {
            title.setText(R.string.researcher_feedback_general_title);
            loadGeneralFeedback();
        }
    }

    private void loadStudyFeedback(@NonNull String studyId) {
        FeedbackApiClient.fetchStudyFeedback(
                this,
                httpClient,
                studyId,
                new FeedbackApiClient.FeedbackListListener() {
                    @Override
                    public void onLoaded(@NonNull List<FeedbackApiClient.FeedbackItem> feedback) {
                        runOnUiThread(() -> showFeedback(feedback));
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> showError(error));
                    }
                }
        );
    }

    private void loadGeneralFeedback() {
        FeedbackApiClient.fetchGeneralFeedback(
                this,
                httpClient,
                new FeedbackApiClient.FeedbackListListener() {
                    @Override
                    public void onLoaded(@NonNull List<FeedbackApiClient.FeedbackItem> feedback) {
                        runOnUiThread(() -> showFeedback(feedback));
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> showError(error));
                    }
                }
        );
    }

    private void showFeedback(@NonNull List<FeedbackApiClient.FeedbackItem> feedback) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (feedback.isEmpty()) {
            message.setText(R.string.researcher_feedback_empty);
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        int bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        for (FeedbackApiClient.FeedbackItem item : feedback) {
            container.addView(buildFeedbackCard(item), cardParams(bottomMargin));
        }
    }

    @NonNull
    private View buildFeedbackCard(@NonNull FeedbackApiClient.FeedbackItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.rounded_card);
        int padding = getResources().getDimensionPixelSize(R.dimen.field_padding);
        card.setPadding(padding, padding, padding, padding);

        TextView header = new TextView(this);
        String ratingText = item.rating == null
                ? getString(R.string.researcher_feedback_no_rating)
                : getString(R.string.researcher_feedback_rating, item.rating);
        header.setText(getString(
                R.string.researcher_feedback_header,
                categoryLabel(item.category),
                ratingText
        ));
        header.setTextColor(getColor(R.color.sky_blue_dark));
        header.setTextSize(16);
        card.addView(header);

        if (item.comment != null && !item.comment.isEmpty()) {
            TextView comment = new TextView(this);
            comment.setText(item.comment);
            comment.setTextColor(getColor(R.color.black));
            comment.setTextSize(14);
            LinearLayout.LayoutParams commentParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            commentParams.topMargin = getResources().getDimensionPixelSize(R.dimen.space_8);
            card.addView(comment, commentParams);
        }

        TextView dateView = new TextView(this);
        dateView.setText(DATE_FORMAT.format(item.createdAt));
        dateView.setTextColor(getColor(R.color.hint_grey));
        dateView.setTextSize(12);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        dateParams.topMargin = getResources().getDimensionPixelSize(R.dimen.space_8);
        card.addView(dateView, dateParams);

        return card;
    }

    @NonNull
    private LinearLayout.LayoutParams cardParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    @NonNull
    private String categoryLabel(@NonNull String category) {
        switch (category) {
            case "study":
                return getString(R.string.feedback_category_study);
            case "technical":
                return getString(R.string.feedback_category_technical);
            default:
                return getString(R.string.feedback_category_general);
        }
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();
        message.setText(error.isEmpty() ? getString(R.string.researcher_feedback_error) : error);
        message.setVisibility(View.VISIBLE);
    }
}