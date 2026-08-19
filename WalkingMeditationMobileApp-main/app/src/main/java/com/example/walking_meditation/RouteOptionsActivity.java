package com.example.walking_meditation;

import com.example.walking_meditation.adapters.RouteOptionsAdapter;
import com.example.walking_meditation.models.ApiResponse;
import com.example.walking_meditation.models.RouteOption;
import com.example.walking_meditation.models.ParkItem;
import com.example.walking_meditation.models.LatLon;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;

import com.example.walking_meditation.utils.CacheFiles;

public class RouteOptionsActivity extends AppCompatActivity implements RouteOptionsAdapter.OnRouteClickListener {

    // Change to your server base (emulator -> host machine)
    private static final String BASE_URL = "http://10.0.2.2:8000";
    private static final String URL_WITHOUT_PARK = BASE_URL + "/route/generate-script/without-park";
    private static final String URL_WITH_PARK = BASE_URL + "/route/generate-script/park";

    private static final MediaType JSON
            = MediaType.get("application/json; charset=utf-8");

    private final Gson gson = new Gson();
    private final List<RouteOption> options = new ArrayList<>();

    private TextView tvHeader;
    private TextView tvEmpty;
    private RecyclerView rvRoutes;

    private View loadingOverlay;

    // Keep parsed response in memory so we can access origin/destination on click
    private ApiResponse resp;

    // Long timeouts (>20s)
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // allow long server generation
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build();

    private volatile boolean isLoading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_options);

        tvHeader = findViewById(R.id.tv_header);
        tvEmpty = findViewById(R.id.tv_empty);
        rvRoutes = findViewById(R.id.rv_routes);
        loadingOverlay = findViewById(R.id.loading_overlay);

        // Read file path (instead of huge JSON string extra)
        String path = getIntent().getStringExtra(EXTRA_ROUTE_JSON_PATH);
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "Missing route file path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String json;
        try {
            json = CacheFiles.readText(path);
        } catch (IOException ioe) {
            Toast.makeText(this, "Failed to read route file: " + ioe.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (json.trim().isEmpty()) {
            Toast.makeText(this, "Route file is empty", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            resp = gson.fromJson(json, ApiResponse.class);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to parse route JSON", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Build options list: baseline always first
        if (resp != null && resp.baseline != null) {
            options.add(RouteOption.fromBaseline(resp));
        }
        if (resp != null && resp.parks != null) {
            for (ParkItem p : resp.parks) {
                options.add(RouteOption.fromPark(p));
            }
        }

        int parkCount = (resp != null && resp.parks != null) ? resp.parks.size() : 0;
        tvHeader.setText(String.format(Locale.US,
                "Choose a route (Baseline + %d park option%s)",
                parkCount,
                parkCount == 1 ? "" : "s"));

        tvEmpty.setVisibility(parkCount == 0 ? View.VISIBLE : View.GONE);

        rvRoutes.setLayoutManager(new LinearLayoutManager(this));
        rvRoutes.setAdapter(new RouteOptionsAdapter(options, this));

        // Prevent back during loading (nice UX for long call)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isLoading) {
                    Toast.makeText(RouteOptionsActivity.this,
                            "Please wait—generating script...",
                            Toast.LENGTH_SHORT).show();
                } else {
                    setEnabled(false);
                    RouteOptionsActivity.this.onBackPressed();
                }
            }
        });
    }

    @Override
    public void onRouteClicked(RouteOption option) {
        if (isLoading) return;

        if (resp == null || resp.origin == null || resp.destination == null) {
            Toast.makeText(this, "Route data incomplete", Toast.LENGTH_SHORT).show();
            return;
        }

        // Replace this with a real input later
        String context = "stressful meeting";

        if (option.type == RouteOption.Type.BASELINE) {
            callGenerateWithoutPark(resp.origin, resp.destination, resp.baseline.duration_s, context);
        } else {
            ParkItem selectedParkItem = findParkItemByPlaceId(option.parkPlaceId);
            if (selectedParkItem == null || selectedParkItem.park == null) {
                Toast.makeText(this, "Park data missing", Toast.LENGTH_SHORT).show();
                return;
            }

            callGenerateWithPark(
                    resp.origin,
                    resp.destination,
                    selectedParkItem.park.lat,
                    selectedParkItem.park.lon,
                    context
            );
        }
    }

    private ParkItem findParkItemByPlaceId(String placeId) {
        if (placeId == null || resp == null || resp.parks == null) return null;
        for (ParkItem p : resp.parks) {
            if (p != null && p.park != null && placeId.equals(p.park.place_id)) {
                return p;
            }
        }
        return null;
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        rvRoutes.setEnabled(!loading);
    }

    private void callGenerateWithoutPark(LatLon source, LatLon destination, int totalWalkingTime, String context) {
        try {
            JSONObject payload = new JSONObject();

            JSONObject src = new JSONObject();
            src.put("lat", source.lat);
            src.put("lon", source.lon);

            JSONObject dst = new JSONObject();
            dst.put("lat", destination.lat);
            dst.put("lon", destination.lon);

            payload.put("source", src);
            payload.put("destination", dst);
            payload.put("total_walking_time", totalWalkingTime);
            payload.put("context", context);

            postJson(URL_WITHOUT_PARK, payload);

        } catch (Exception e) {
            Toast.makeText(this, "JSON build error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void callGenerateWithPark(LatLon source, LatLon destination, double parkLat, double parkLon, String context) {
        try {
            JSONObject payload = new JSONObject();

            JSONObject src = new JSONObject();
            src.put("lat", source.lat);
            src.put("lon", source.lon);

            JSONObject dst = new JSONObject();
            dst.put("lat", destination.lat);
            dst.put("lon", destination.lon);

            JSONObject park = new JSONObject();
            park.put("lat", parkLat);
            park.put("lon", parkLon);

            payload.put("source", src);
            payload.put("destination", dst);
            payload.put("park", park);
            payload.put("context", context);

            payload.put("to_park_time", 500);
            payload.put("park_time", 1000);
            payload.put("park_to_destination_time", 600);

            postJson(URL_WITH_PARK, payload);

        } catch (Exception e) {
            Toast.makeText(this, "JSON build error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void postJson(String url, JSONObject payload) {
        setLoading(true);

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request req = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(RouteOptionsActivity.this,
                            "Generate failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String respBody = response.body() != null ? response.body().string() : "";

                runOnUiThread(() -> {
                    setLoading(false);

                    if (!response.isSuccessful()) {
                        Toast.makeText(RouteOptionsActivity.this,
                                "Server error: " + response.code() + "\n" + respBody,
                                Toast.LENGTH_LONG).show();
                        response.close();
                        return;
                    }

                    try {
                        // respBody is JSON from your backend (may be large)
                        // Save entire JSON to cache and pass file path to ScriptActivity
                        String scriptPath = CacheFiles.writeText(
                                RouteOptionsActivity.this,
                                "scripts",
                                "script",
                                ".json",
                                respBody
                                );

                        Intent intent = new Intent(RouteOptionsActivity.this, ScriptActivity.class);
                        intent.putExtra(EXTRA_SCRIPT_JSON_PATH, scriptPath);
                        startActivity(intent);

                    } catch (IOException ioe) {
                        Toast.makeText(RouteOptionsActivity.this,
                                "Failed to save script response: " + ioe.getMessage(),
                                Toast.LENGTH_LONG).show();
                    } finally {
                        response.close();
                    }
                });
            }
        });
    }

}