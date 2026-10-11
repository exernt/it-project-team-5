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
import static com.example.walking_meditation.nav.Extras.EXTRA_CONTEXT;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_POLYLINES;
import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_STUDY_PARAMETERS;

import com.example.walking_meditation.utils.CacheFiles;

public class RouteOptionsActivity extends AppCompatActivity implements RouteOptionsAdapter.OnRouteClickListener {

    // Change to your server base (emulator -> host machine)
    private static final String BASE_URL = BuildConfig.SERVER_BASE_URL;
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
            .callTimeout(240, TimeUnit.SECONDS)
            .build();

    private volatile boolean isLoading = false;
    private Call inFlightCall;
    private String meditationContext;
    private String studyParameters;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_options);

        tvHeader = findViewById(R.id.tv_header);
        tvEmpty = findViewById(R.id.tv_empty);
        rvRoutes = findViewById(R.id.rv_routes);
        loadingOverlay = findViewById(R.id.loading_overlay);
        meditationContext = getIntent().getStringExtra(EXTRA_CONTEXT);
        if (meditationContext == null || meditationContext.trim().isEmpty()) {
            meditationContext = "general wellbeing";
        }
        studyParameters = getIntent().getStringExtra(EXTRA_STUDY_PARAMETERS);
        if (studyParameters == null) studyParameters = "";

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

        boolean baselineFits = resp != null
                && resp.baseline != null
                && resp.total_travel_time != null
                && resp.baseline.duration_s <= resp.total_travel_time;
        if (baselineFits) {
            options.add(RouteOption.fromBaseline(resp));
        }
        if (resp != null && resp.parks != null) {
            for (ParkItem p : resp.parks) {
                options.add(RouteOption.fromPark(p));
            }
        }

        int parkCount = (resp != null && resp.parks != null) ? resp.parks.size() : 0;
        if (options.isEmpty()) {
            tvHeader.setText("No route fits the available time");
            tvEmpty.setText("Increase the available time or choose closer locations.");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvHeader.setText(String.format(Locale.US,
                    "Choose a route (Baseline + %d park option%s)",
                    parkCount,
                    parkCount == 1 ? "" : "s"));
            tvEmpty.setVisibility(parkCount == 0 ? View.VISIBLE : View.GONE);
        }

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
                    getOnBackPressedDispatcher().onBackPressed();
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

        if (option.type == RouteOption.Type.BASELINE) {
            callGenerateWithoutPark(
                    resp.origin, resp.destination, resp.baseline.duration_s,
                    meditationContext, option);
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
                    meditationContext,
                    selectedParkItem,
                    option
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

    private void callGenerateWithoutPark(LatLon source, LatLon destination, int totalWalkingTime,
                                         String context, RouteOption option) {
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
            payload.put("study_parameters", studyParameters);

            ArrayList<String> polylines = new ArrayList<>();
            if (option.polyline != null && !option.polyline.isEmpty()) polylines.add(option.polyline);
            postJson(URL_WITHOUT_PARK, payload, polylines);

        } catch (Exception e) {
            Toast.makeText(this, "JSON build error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void callGenerateWithPark(LatLon source, LatLon destination, double parkLat, double parkLon,
                                      String context, ParkItem parkItem, RouteOption option) {
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
            payload.put("study_parameters", studyParameters);

            payload.put("to_park_time", parkItem.to_park_s);
            payload.put("park_time", parkItem.slack_s);
            payload.put("park_to_destination_time", parkItem.park_to_destination_s);

            ArrayList<String> polylines = new ArrayList<>();
            if (option.polyline != null && !option.polyline.isEmpty()) polylines.add(option.polyline);
            if (option.polylineFromPark != null && !option.polylineFromPark.isEmpty()) {
                polylines.add(option.polylineFromPark);
            }
            postJson(URL_WITH_PARK, payload, polylines);

        } catch (Exception e) {
            Toast.makeText(this, "JSON build error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void postJson(String url, JSONObject payload, ArrayList<String> routePolylines) {
        setLoading(true);

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);
        if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty()) {
            requestBuilder.header("X-App-Token", BuildConfig.SERVER_ACCESS_TOKEN);
        }
        Request req = requestBuilder.build();

        if (inFlightCall != null) inFlightCall.cancel();
        inFlightCall = httpClient.newCall(req);
        inFlightCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    Toast.makeText(RouteOptionsActivity.this,
                            "Generate failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String respBody;
                final boolean successful = response.isSuccessful();
                final int responseCode = response.code();
                try (Response closedResponse = response) {
                    respBody = closedResponse.body() != null ? closedResponse.body().string() : "";
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);

                    if (!successful) {
                        Toast.makeText(RouteOptionsActivity.this,
                                "Server error: " + responseCode + "\n" + respBody,
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    try {
                        // Save the complete JSON response for the temporary inspection screen.
                        String scriptPath = CacheFiles.writeText(
                                RouteOptionsActivity.this,
                                "scripts",
                                "script",
                                ".json",
                                respBody
                                );

                        Intent intent = new Intent(RouteOptionsActivity.this, ScriptResultActivity.class);
                        intent.putExtra(EXTRA_SCRIPT_JSON_PATH, scriptPath);
                        startActivity(intent);

                    } catch (IOException ioe) {
                        Toast.makeText(RouteOptionsActivity.this,
                                "Failed to save script response: " + ioe.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (inFlightCall != null) {
            inFlightCall.cancel();
            inFlightCall = null;
        }
        super.onDestroy();
    }

}
