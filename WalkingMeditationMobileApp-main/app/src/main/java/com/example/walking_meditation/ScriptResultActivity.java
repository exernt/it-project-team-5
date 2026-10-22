package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_PLAN_PATH;

import android.content.Intent;
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

/** Temporary screen used to inspect the raw script-generation response. */
public class ScriptResultActivity extends AppCompatActivity {

    private RoutePlan routePlan;
    private final List<ScheduledNarration> scheduledNarrations =
            new ArrayList<>();
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_result);

        TextView responseView = findViewById(R.id.tv_script_response);
        Button startButton = findViewById(R.id.btn_start_navigation);

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
            String scriptJson = CacheFiles.readText(scriptJsonPath);
            routePlan = RoutePlanFiles.read(routePlanPath);

            responseView.setText(formatResponse(scriptJson));

            scheduledNarrations.clear();
            scheduledNarrations.addAll(ScriptNarrationParser.parse(scriptJson, routePlan));

            NarrationScheduler.resolve(routePlan, scheduledNarrations);

            // Audio preparation will be added here next.

        } catch (Exception e) {
            Toast.makeText(this, "Unable to load route or script: "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
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
