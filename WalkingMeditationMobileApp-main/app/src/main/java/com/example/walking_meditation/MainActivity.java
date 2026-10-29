package com.example.walking_meditation;

import android.content.Intent;
import com.example.walking_meditation.audio.CurrentWalk;
import com.example.walking_meditation.BuildConfig;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.StudyConfigClient;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.RoutePlanFiles;
import com.example.walking_meditation.utils.UserApiClient;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private TextView researcherPendingNotice;
    private Button btnExplore, btnResearcherDashboard, btnStudy, btnLogOut, btnFeedback, btnAdminDashboard, btnBecomeResearcher, btnProfile;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnExplore = findViewById(R.id.btnExplore);
        findViewById(R.id.btnResumeWalk).setOnClickListener(v ->
                startActivity(new Intent(this, MeditationPlaybackActivity.class)));
        btnResearcherDashboard = findViewById(R.id.btnResearcherDashboard);
        btnStudy = findViewById(R.id.btnStudy);
        btnLogOut = findViewById(R.id.btnLogOut);
        btnFeedback = findViewById(R.id.btnFeedback);
        btnAdminDashboard = findViewById(R.id.btnAdminDashboard);
        btnBecomeResearcher = findViewById(R.id.btnBecomeResearcher);
        researcherPendingNotice = findViewById(R.id.researcherPendingNotice);
        btnProfile = findViewById(R.id.btnProfile);

        btnResearcherDashboard.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, ResearcherStudyListActivity.class)));
        btnAdminDashboard.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, AdminDashboardActivity.class)));
        btnBecomeResearcher.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, BecomeResearcherActivity.class)));
        btnProfile.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, ProfileActivity.class)));

        applyRoleVisibility();

        btnExplore.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, PathSelectionActivity.class);
            startActivity(intent);
        });

        btnStudy.setOnClickListener(view -> openStudyManagement());

        btnFeedback.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, FeedbackActivity.class))
        );

        btnLogOut.setOnClickListener(view -> performLogout());
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ready;
        try { ready = new CurrentWalk(getFilesDir()).read(true).ready; }
        catch (Exception ignored) { ready = false; }
        findViewById(R.id.btnResumeWalk).setVisibility(ready ? View.VISIBLE : View.GONE);
        btnLogOut.setOnClickListener(view -> performLogout());
        refreshRole();
    }

    private void applyRoleVisibility() {
        boolean admin = SessionManager.isAdmin(this);
        boolean researcher = "researcher".equals(SessionManager.getUserRole(this));

        btnResearcherDashboard.setVisibility(
                SessionManager.isVerifiedResearcher(this) ? View.VISIBLE : View.GONE);
        btnAdminDashboard.setVisibility(admin ? View.VISIBLE : View.GONE);
        btnBecomeResearcher.setVisibility(
                !admin && !researcher ? View.VISIBLE : View.GONE);
        researcherPendingNotice.setVisibility(
                researcher && !SessionManager.isVerifiedResearcher(this)
                        ? View.VISIBLE : View.GONE);
    }

    /** Re-checks role and approval status so changes made elsewhere show up without re-login. */
    private void refreshRole() {
        UserApiClient.fetchCurrentUser(this, httpClient, new UserApiClient.CurrentUserListener() {
            @Override
            public void onLoaded(@NonNull String role, boolean researcherVerified) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String token = SessionManager.getAccessToken(MainActivity.this);
                    if (token == null || token.isEmpty()) {
                        return; // logged out while the request was in flight
                    }
                    SessionManager.saveUserInfo(MainActivity.this, role, researcherVerified);
                    applyRoleVisibility();
                });
            }

            @Override
            public void onFailure(@NonNull String message) {
                // Keep showing the cached role if the refresh fails (offline, etc.).
            }
        });
    }

    private void performLogout() {
        String refreshToken = SessionManager.getRefreshToken(this);
        btnLogOut.setEnabled(false);

        try {
            new CurrentWalk(getFilesDir()).clear();
        } catch (IOException error) {
            // Keep the session active so cleanup can be retried before completing logout.
            Toast.makeText(this, "Unable to delete saved walk. Please retry logout.",
                    Toast.LENGTH_LONG).show();
            btnLogOut.setEnabled(true);
            return;
        }

        // Remove all account, study, route, script, and generated-audio data immediately.
        SessionManager.clearSession(this);
        RoutePlanFiles.clearAll(this);
        CacheFiles.clearSensitiveFiles(this);

        if (refreshToken != null && !refreshToken.isEmpty()) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("refresh_token", refreshToken);
                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.get("application/json; charset=utf-8")
                );
                Request request = new Request.Builder()
                        .url(BuildConfig.SERVER_BASE_URL + "/auth/logout")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        finishLogout(false);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        boolean revokedRemotely = response.isSuccessful();
                        response.close();
                        finishLogout(revokedRemotely);
                    }
                });
                return;
            } catch (Exception ignored) {
                finishLogout(false);
                return;
            }
        }

        finishLogout(true);
    }

    private void finishLogout(boolean revokedRemotely) {
        runOnUiThread(() -> {
            String message = revokedRemotely
                    ? "Logged out successfully"
                    : "Logged out locally; server session could not be revoked";
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void openStudyManagement() {
        btnStudy.setEnabled(false);
        btnStudy.setText(R.string.checking_study_status);

        StudyConfigClient.fetchCurrent(this, httpClient, new StudyConfigClient.Listener() {
            @Override
            public void onLoaded(boolean participating, @NonNull String studyId, @NonNull String parameters) {
                runOnUiThread(() -> {
                    resetStudyButton();
                    Intent intent;
                    if (participating && !studyId.isEmpty()) {
                        intent = new Intent(MainActivity.this, StudyUserManageActivity.class);
                    } else {
                        SessionManager.clearStudyConfiguration(MainActivity.this);
                        intent = new Intent(MainActivity.this, StudyUnenrolledActivity.class);
                    }
                    startActivity(intent);
                });
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> {
                    resetStudyButton();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resetStudyButton() {
        btnStudy.setEnabled(true);
        btnStudy.setText(R.string.manage_study);
    }
}
