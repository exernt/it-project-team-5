package com.example.walking_meditation;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.AdminApiClient;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

/**
 * Admin-only screen: jump to general (non-study) app feedback, and review
 * + approve researcher registrations that are still pending, without
 * needing direct database access.
 */
public class AdminDashboardActivity extends AppCompatActivity {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
                    .withZone(ZoneId.systemDefault());

    private final OkHttpClient httpClient = new OkHttpClient();

    private ProgressBar progress;
    private TextView message;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);
        setTitle(R.string.admin_dashboard_heading);

        progress = findViewById(R.id.adminPendingProgress);
        message = findViewById(R.id.adminPendingMessage);
        container = findViewById(R.id.adminPendingContainer);

        findViewById(R.id.btnAdminGeneralFeedback).setOnClickListener(view ->
                startActivity(ResearcherFeedbackActivity.createIntentForGeneralFeedback(this))
        );

        loadPendingResearchers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check in case a registration was approved and we came back.
        if (container.getChildCount() == 0 && progress.getVisibility() == View.GONE) {
            loadPendingResearchers();
        }
    }

    private void loadPendingResearchers() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        container.removeAllViews();

        AdminApiClient.fetchPendingResearchers(
                this,
                httpClient,
                new AdminApiClient.PendingResearchersListener() {
                    @Override
                    public void onLoaded(@NonNull List<AdminApiClient.PendingResearcher> researchers) {
                        runOnUiThread(() -> showPending(researchers));
                    }

                    @Override
                    public void onFailure(@NonNull String message) {
                        runOnUiThread(() -> showError(message));
                    }
                }
        );
    }

    private void showPending(@NonNull List<AdminApiClient.PendingResearcher> researchers) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();

        if (researchers.isEmpty()) {
            message.setText(R.string.admin_pending_researchers_empty);
            message.setVisibility(View.VISIBLE);
            return;
        }

        message.setVisibility(View.GONE);
        int bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        for (AdminApiClient.PendingResearcher researcher : researchers) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = bottomMargin;
            container.addView(buildResearcherCard(researcher), params);
        }
    }

    @NonNull
    private View buildResearcherCard(@NonNull AdminApiClient.PendingResearcher researcher) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.rounded_card);
        int padding = getResources().getDimensionPixelSize(R.dimen.field_padding);
        card.setPadding(padding, padding, padding, padding);

        TextView email = new TextView(this);
        email.setText(researcher.email);
        email.setTextColor(getColor(R.color.black));
        email.setTextSize(16);
        card.addView(email);

        if (researcher.institution != null && !researcher.institution.isEmpty()) {
            TextView institution = new TextView(this);
            institution.setText(researcher.institution);
            institution.setTextColor(getColor(R.color.hint_grey));
            institution.setTextSize(14);
            addWithTopMargin(card, institution);
        }

        TextView requested = new TextView(this);
        requested.setText(getString(
                R.string.admin_pending_requested_on,
                DATE_FORMAT.format(researcher.requestedAt)
        ));
        requested.setTextColor(getColor(R.color.hint_grey));
        requested.setTextSize(12);
        addWithTopMargin(card, requested);

        Button approveButton = new Button(this);
        approveButton.setText(R.string.admin_approve_researcher);
        approveButton.setOnClickListener(view -> approve(researcher, approveButton, card));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        card.addView(approveButton, buttonParams);

        return card;
    }

    private void addWithTopMargin(@NonNull LinearLayout parent, @NonNull View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = getResources().getDimensionPixelSize(R.dimen.space_8);
        parent.addView(child, params);
    }

    private void approve(
            @NonNull AdminApiClient.PendingResearcher researcher,
            @NonNull Button approveButton,
            @NonNull View card
    ) {
        approveButton.setEnabled(false);
        approveButton.setText(R.string.admin_approving);

        AdminApiClient.approveResearcher(
                this,
                httpClient,
                researcher.userId,
                new AdminApiClient.ApproveListener() {
                    @Override
                    public void onApproved() {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(
                                    AdminDashboardActivity.this,
                                    getString(R.string.admin_approved_researcher, researcher.email),
                                    Toast.LENGTH_SHORT
                            ).show();
                            container.removeView(card);
                            if (container.getChildCount() == 0) {
                                message.setText(R.string.admin_pending_researchers_empty);
                                message.setVisibility(View.VISIBLE);
                            }
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String errorMessage) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            approveButton.setEnabled(true);
                            approveButton.setText(R.string.admin_approve_researcher);
                            Toast.makeText(AdminDashboardActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        );
    }

    private void showError(@NonNull String error) {
        progress.setVisibility(View.GONE);
        container.removeAllViews();
        message.setText(error.isEmpty() ? getString(R.string.admin_pending_researchers_error) : error);
        message.setVisibility(View.VISIBLE);
    }
}