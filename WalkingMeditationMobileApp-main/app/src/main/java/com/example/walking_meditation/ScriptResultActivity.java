package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_PLAN_PATH;

import android.content.Intent;
import com.example.walking_meditation.audio.CurrentWalk;
import com.example.walking_meditation.audio.TtsClient;
import com.example.walking_meditation.audio.WalkAudioPreparer;
import com.example.walking_meditation.audio.PollyPayload;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.script.ScriptNarrationParser;
import com.example.walking_meditation.script.NarrationScheduler;
import com.example.walking_meditation.utils.RoutePlanFiles;
import com.example.walking_meditation.models.ScheduledNarration;
import com.example.walking_meditation.utils.CacheFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Prepares all narration before enabling the walk. */
public class ScriptResultActivity extends AppCompatActivity {
    // A single worker prevents a cancelled activity writing into the next walk.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Future<?> preparation;
    private WalkAudioPreparer preparer;
    private Button startButton;
    private Button retryButton;
    private TextView status;
    private String scriptJson;

    private RoutePlan routePlan;
    private final List<ScheduledNarration> scheduledNarrations =
            new ArrayList<>();
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_result);

        TextView responseView = findViewById(R.id.tv_script_response);
        startButton = findViewById(R.id.btn_start_navigation);
        retryButton = findViewById(R.id.btn_retry_audio);
        status = findViewById(R.id.tv_audio_status);
        retryButton.setOnClickListener(v -> prepareAudio());
        findViewById(R.id.btn_discard_walk).setOnClickListener(v -> {
            cancelPreparation();
            WORKER.submit(() -> {
                try { new CurrentWalk(getFilesDir()).finish(() -> {}); }
                catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
            });
            finish();
        });
        startButton.setOnClickListener(v -> {
            startActivity(new Intent(this, MeditationPlaybackActivity.class));
            finish();
        });

        startButton.setEnabled(false);

        String scriptJsonPath = getIntent().getStringExtra(
                EXTRA_SCRIPT_JSON_PATH);
        String routePlanPath = getIntent().getStringExtra(
                EXTRA_ROUTE_PLAN_PATH);

        if (scriptJsonPath == null || routePlanPath == null) {
            Toast.makeText(this, "Missing script or route plan",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            scriptJson = CacheFiles.readText(scriptJsonPath);
            routePlan = RoutePlanFiles.read(this, routePlanPath);

            responseView.setText(formatResponse(scriptJson));

            scheduledNarrations.clear();
            scheduledNarrations.addAll(ScriptNarrationParser.parse(scriptJson, routePlan));

            NarrationScheduler.resolve(routePlan, scheduledNarrations);

            prepareAudio();

        } catch (Exception e) {
            Toast.makeText(this, "Unable to load route or script: "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
    }

    private void prepareAudio() {
        cancelPreparation();
        startButton.setEnabled(false);
        retryButton.setEnabled(false);
        status.setText("Preparing walk audio…");
        final WalkAudioPreparer current;
        try {
            current = new WalkAudioPreparer(new CurrentWalk(getFilesDir()),
                    new TtsClient(getApplicationContext(), new PollyPayload()));
            preparer = current;
        } catch (Exception error) {
            status.setText("Audio preparation failed: " + error.getMessage());
            retryButton.setEnabled(true);
            return;
        }
        preparation = WORKER.submit(() -> {
            try {
                current.prepare(routePlan, scriptJson, scheduledNarrations, (completed, total) ->
                        runOnUiThread(() -> {
                            if (!isDestroyed() && !isFinishing() && !current.isCancelled())
                                status.setText("Preparing audio " + (completed + 1) + " of " + total);
                        }));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || current.isCancelled()) return;
                    status.setText("Audio ready. Start your walk.");
                    startButton.setEnabled(true);
                });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted() || current.isCancelled()) return;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || current.isCancelled()) return;
                    status.setText("Audio preparation failed: " + error.getMessage());
                    retryButton.setEnabled(true);
                });
            }
        });
    }

    private void cancelPreparation() {
        if (preparer != null) preparer.cancel();
        if (preparation != null) preparation.cancel(true);
    }

    @Override protected void onDestroy() {
        cancelPreparation();
        super.onDestroy();
    }

    private static String formatResponse(String response) {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.isEmpty()) return "(empty response)";
        try {
            if (trimmed.startsWith("{")) return new JSONObject(trimmed).toString(2);
            if (trimmed.startsWith("[")) return new JSONArray(trimmed).toString(2);
        } catch (Exception ignored) {
            // Display non-JSON and malformed test responses verbatim.
        }
        return trimmed;
    }
}
