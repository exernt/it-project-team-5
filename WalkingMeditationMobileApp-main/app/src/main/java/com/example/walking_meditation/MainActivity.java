package com.example.walking_meditation;

import android.content.Intent;
import com.example.walking_meditation.audio.CurrentWalk;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.StudyConfigClient;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.RoutePlanFiles;

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

    private Button btnExplore, btnResearcherDashboard, btnStudy, btnLogOut, btnFeedback, btnAdminDashboard, btnBecomeResearcher;
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

        if (SessionManager.isVerifiedResearcher(this)) {
            btnResearcherDashboard.setVisibility(View.VISIBLE);
            btnResearcherDashboard.setOnClickListener(view -> {
                Intent intent = new Intent(MainActivity.this, ResearcherStudyListActivity.class);
                startActivity(intent);
            });
        }

        if (SessionManager.isAdmin(this)) {
            btnAdminDashboard.setVisibility(View.VISIBLE);
            btnAdminDashboard.setOnClickListener(view ->
                    startActivity(new Intent(MainActivity.this, AdminDashboardActivity.class))
            );
        }

        if (!SessionManager.isAdmin(this) && !"researcher".equals(SessionManager.getUserRole(this))) {
            btnBecomeResearcher.setVisibility(View.VISIBLE);
            btnBecomeResearcher.setOnClickListener(view ->
                    startActivity(new Intent(MainActivity.this, BecomeResearcherActivity.class))
            );
        }

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
    }

    private void performLogout() {
        String refreshToken = SessionManager.getRefreshToken(this);
        btnLogOut.setEnabled(false);

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
