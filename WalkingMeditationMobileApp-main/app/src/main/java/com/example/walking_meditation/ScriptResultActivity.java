package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;

import android.os.Bundle;
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
        String path = getIntent().getStringExtra(EXTRA_SCRIPT_JSON_PATH);
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "Missing script response", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

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
