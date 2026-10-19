package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_PLAN_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_TTS_SPEAKER;
import static com.example.walking_meditation.nav.Extras.EXTRA_TTS_INSTRUCT;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.CacheFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/** Temporary screen used to inspect the raw script-generation response. */
public class ScriptResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_result);

        TextView responseView = findViewById(R.id.tv_script_response);
        Button startNavigation = findViewById(R.id.btn_start_navigation);
        String path = getIntent().getStringExtra(EXTRA_SCRIPT_JSON_PATH);
        String routePlanPath = getIntent().getStringExtra(EXTRA_ROUTE_PLAN_PATH);
        String ttsSpeaker = getIntent().getStringExtra(EXTRA_TTS_SPEAKER);
        String ttsInstruct = getIntent().getStringExtra(EXTRA_TTS_INSTRUCT);

        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "Missing script response", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        startNavigation.setEnabled(routePlanPath != null && !routePlanPath.trim().isEmpty());

        startNavigation.setOnClickListener(view -> {
            Intent intent = new Intent(this, MeditationPlaybackActivity.class);

            intent.putExtra(EXTRA_SCRIPT_JSON_PATH, path);
            intent.putExtra(EXTRA_ROUTE_PLAN_PATH, routePlanPath);

            intent.putExtra(EXTRA_TTS_SPEAKER, ttsSpeaker);
            intent.putExtra(EXTRA_TTS_INSTRUCT, ttsInstruct);

            startActivity(intent);
        });

        try {
            responseView.setText(formatResponse(CacheFiles.readText(path)));
        } catch (IOException exception) {
            Toast.makeText(this,
                    "Unable to read script response: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
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
