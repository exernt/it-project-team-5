package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_JSON_PATH;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.StudyConfigClient;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PathSelectionActivity extends AppCompatActivity {

    private PlacesClient placesClient;

    // Selected place IDs (only set when user clicks a suggestion)
    @Nullable private String selectedSourcePlaceId;
    @Nullable private String selectedDestinationPlaceId;

    // Track whether text change came from a user selection vs manual typing
    private boolean sourceSelectionInProgress = false;
    private boolean destSelectionInProgress = false;

    // Autocomplete prediction lists (kept in sync with adapter)
    private final List<AutocompletePrediction> sourcePredictions = new ArrayList<>();
    private final List<AutocompletePrediction> destPredictions = new ArrayList<>();

    // Debounce
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long AUTOCOMPLETE_DEBOUNCE_MS = 350;

    // Networking
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build();
    private @Nullable Call inFlightCall;

    private static final String ROUTE_URL = BuildConfig.SERVER_BASE_URL + "/route/generate-path";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // UI
    private AutoCompleteTextView etSource;
    private AutoCompleteTextView etDestination;
    private Button btnFindPath;
    private EditText etTotalMinutes;
    private EditText etContext;
    private TextView tvStudyStatus;
    private boolean participatingInStudy = false;
    private String studyParameters = "";
    private String contextBeforeStudy = "";
    private boolean routeBusy = false;
    private boolean studyRefreshPending = false;
    private boolean launchedFromStudyManagement = false;

    private static final String STATE_SOURCE_PLACE_ID = "source_place_id";
    private static final String STATE_DESTINATION_PLACE_ID = "destination_place_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_path_selection);

        etSource = findViewById(R.id.et_source);
        etDestination = findViewById(R.id.et_destination);
        etTotalMinutes = findViewById(R.id.et_total_minutes);
        etContext = findViewById(R.id.et_context);
        tvStudyStatus = findViewById(R.id.tv_study_status);
        btnFindPath = findViewById(R.id.btn_find_path);
        launchedFromStudyManagement = getIntent().getBooleanExtra(
                com.example.walking_meditation.nav.Extras.EXTRA_FROM_STUDY_MANAGE,
                false
        );
        applyStudyConfiguration(
                SessionManager.hasActiveStudy(this),
                SessionManager.getStudyParameters(this)
        );

        String mapsKey = getString(R.string.google_maps_key).trim();
        if (mapsKey.isEmpty()) {
            btnFindPath.setEnabled(false);
            Toast.makeText(this,
                    "Set MAPS_API_KEY in Gradle properties to enable route search.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(
                    getApplicationContext(),
                    mapsKey
            );
        }

        placesClient = Places.createClient(this);

        setupAutocomplete(etSource, true);
        setupAutocomplete(etDestination, false);

        btnFindPath.setOnClickListener(v -> {
            if (selectedSourcePlaceId == null || selectedDestinationPlaceId == null) {
                Toast.makeText(
                        PathSelectionActivity.this,
                        "Please pick both source and destination from suggestions.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            int totalMinutes;
            try {
                totalMinutes = Integer.parseInt(etTotalMinutes.getText().toString().trim());
            } catch (NumberFormatException e) {
                etTotalMinutes.setError("Enter a time from 1 to 240 minutes");
                return;
            }
            if (totalMinutes < 1 || totalMinutes > 240) {
                etTotalMinutes.setError("Enter a time from 1 to 240 minutes");
                return;
            }
            String context = participatingInStudy
                    ? "the goals defined by the current study"
                    : etContext.getText().toString().trim();
            if (!participatingInStudy && context.isEmpty()) {
                etContext.setError("Enter a meditation purpose");
                return;
            }
            setBusy(true);
            fetchLatLngAndCallApi(totalMinutes * 60, context);
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SOURCE_PLACE_ID, selectedSourcePlaceId);
        outState.putString(STATE_DESTINATION_PLACE_ID, selectedDestinationPlaceId);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        selectedSourcePlaceId = savedInstanceState.getString(STATE_SOURCE_PLACE_ID);
        selectedDestinationPlaceId = savedInstanceState.getString(STATE_DESTINATION_PLACE_ID);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Cancel pending debounce runnable
        mainHandler.removeCallbacksAndMessages(null);

        // Cancel in-flight OkHttp call to avoid callbacks hitting a dead Activity
        if (inFlightCall != null) {
            inFlightCall.cancel();
            inFlightCall = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (btnFindPath != null && !getString(R.string.google_maps_key).trim().isEmpty()) {
            if (inFlightCall == null) {
                setBusy(false);
            }
            if (launchedFromStudyManagement && SessionManager.hasActiveStudy(this)) {
                applyStudyConfiguration(true, SessionManager.getStudyParameters(this));
            } else {
                refreshStudyConfiguration();
            }
        }
    }

    private void setBusy(boolean busy) {
        routeBusy = busy;
        updateFindPathEnabled();
        // If you have a ProgressBar, toggle it here as well.
    }

    private void updateFindPathEnabled() {
        btnFindPath.setEnabled(!routeBusy && !studyRefreshPending);
    }

    private void refreshStudyConfiguration() {
        if (studyRefreshPending) return;
        studyRefreshPending = true;
        updateFindPathEnabled();

        StudyConfigClient.fetchCurrent(this, httpClient, new StudyConfigClient.Listener() {
            @Override
            public void onLoaded(boolean participating, @NonNull String studyId, @NonNull String parameters) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    studyRefreshPending = false;
                    if (participating) {
                        SessionManager.cacheStudyConfiguration(
                                PathSelectionActivity.this,
                                studyId,
                                parameters
                        );
                    } else {
                        SessionManager.clearStudyConfiguration(PathSelectionActivity.this);
                    }
                    applyStudyConfiguration(participating, parameters);
                    updateFindPathEnabled();
                });
            }

            @Override
            public void onFailure(@NonNull String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    studyRefreshPending = false;
                    updateFindPathEnabled();
                    Toast.makeText(
                            PathSelectionActivity.this,
                            "Using cached study settings: " + message,
                            Toast.LENGTH_SHORT
                    ).show();
                });
            }
        });
    }

    private void applyStudyConfiguration(boolean participating, @NonNull String parameters) {
        if (participating && !participatingInStudy) {
            contextBeforeStudy = etContext.getText().toString();
        }

        participatingInStudy = participating;
        studyParameters = participating ? parameters : "";

        if (participating) {
            etContext.setText("");
            etContext.setHint("Participating in study");
            etContext.setEnabled(false);
            etContext.setFocusable(false);
            etContext.setAlpha(0.55f);
            tvStudyStatus.setVisibility(View.VISIBLE);
        } else {
            etContext.setEnabled(true);
            etContext.setFocusableInTouchMode(true);
            etContext.setAlpha(1.0f);
            etContext.setHint("For example: prepare for a stressful meeting");
            if (!contextBeforeStudy.isEmpty() && etContext.getText().toString().isEmpty()) {
                etContext.setText(contextBeforeStudy);
            }
            tvStudyStatus.setVisibility(View.GONE);
        }
    }

    private void setupAutocomplete(AutoCompleteTextView textView, boolean isSource) {

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>()
        );
        textView.setAdapter(adapter);
        textView.setThreshold(1); // allow suggestions earlier; debounce controls call frequency

        // One-time item click listener (NOT inside the network callback)
        textView.setOnItemClickListener((parent, view, position, id) -> {
            List<AutocompletePrediction> list = isSource ? sourcePredictions : destPredictions;
            if (position < 0 || position >= list.size()) return;

            AutocompletePrediction p = list.get(position);

            if (isSource) {
                sourceSelectionInProgress = true;
                selectedSourcePlaceId = p.getPlaceId();
                // optional: normalize the text to the full prediction
                textView.setText(p.getFullText(null).toString(), false);
                sourceSelectionInProgress = false;
            } else {
                destSelectionInProgress = true;
                selectedDestinationPlaceId = p.getPlaceId();
                textView.setText(p.getFullText(null).toString(), false);
                destSelectionInProgress = false;
            }
        });

        // Debounced text watcher
        final Runnable[] pending = new Runnable[1];

        textView.addTextChangedListener(new TextWatcher() {

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

                // If user manually edits after selecting, invalidate the previously selected placeId
                if (isSource) {
                    if (!sourceSelectionInProgress) selectedSourcePlaceId = null;
                } else {
                    if (!destSelectionInProgress) selectedDestinationPlaceId = null;
                }

                // Cancel previous debounce runnable
                if (pending[0] != null) {
                    mainHandler.removeCallbacks(pending[0]);
                }

                final String query = s.toString().trim();
                if (query.length() < 2) {
                    // Clear suggestions when query is too short
                    adapter.clear();
                    adapter.notifyDataSetChanged();
                    if (isSource) sourcePredictions.clear(); else destPredictions.clear();
                    return;
                }

                pending[0] = () -> requestPredictions(query, textView, adapter, isSource);
                mainHandler.postDelayed(pending[0], AUTOCOMPLETE_DEBOUNCE_MS);
            }

            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void requestPredictions(
            @NonNull String query,
            @NonNull AutoCompleteTextView textView,
            @NonNull ArrayAdapter<String> adapter,
            boolean isSource
    ) {
        FindAutocompletePredictionsRequest request =
                FindAutocompletePredictionsRequest.builder()
                        .setQuery(query)
                        .build();

        placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener(this, response -> {
                    if (!query.equals(textView.getText().toString().trim())) return;
                    List<AutocompletePrediction> predictions = response.getAutocompletePredictions();

                    // Update the stored list that the item-click listener reads
                    if (isSource) {
                        sourcePredictions.clear();
                        sourcePredictions.addAll(predictions);
                    } else {
                        destPredictions.clear();
                        destPredictions.addAll(predictions);
                    }

                    List<String> suggestions = new ArrayList<>(predictions.size());
                    for (AutocompletePrediction p : predictions) {
                        suggestions.add(p.getFullText(null).toString());
                    }

                    adapter.clear();
                    adapter.addAll(suggestions);
                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(this, e -> {
                    if (!query.equals(textView.getText().toString().trim())) return;
                    // Clear stored list to avoid mismatches
                    if (isSource) sourcePredictions.clear(); else destPredictions.clear();

                    adapter.clear();
                    adapter.notifyDataSetChanged();

                    Toast.makeText(
                            PathSelectionActivity.this,
                            "Autocomplete failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT
                    ).show();
                });
    }

    private void fetchLatLngAndCallApi(int totalTravelSeconds, @NonNull String context) {
        // Explicit validation (no assert)
        final String srcId = selectedSourcePlaceId;
        final String dstId = selectedDestinationPlaceId;

        if (srcId == null || dstId == null) {
            setBusy(false);
            Toast.makeText(
                    PathSelectionActivity.this,
                    "Please pick both source and destination from suggestions.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        List<Place.Field> fields = List.of(Place.Field.LOCATION);

        FetchPlaceRequest sourceRequest = FetchPlaceRequest.newInstance(srcId, fields);
        placesClient.fetchPlace(sourceRequest)
                .addOnSuccessListener(this, sourceResponse -> {
                    LatLng sourceLatLng = sourceResponse.getPlace().getLocation();
                    if (sourceLatLng == null) {
                        setBusy(false);
                        Toast.makeText(PathSelectionActivity.this, "Source has no location.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    FetchPlaceRequest destRequest = FetchPlaceRequest.newInstance(dstId, fields);
                    placesClient.fetchPlace(destRequest)
                            .addOnSuccessListener(PathSelectionActivity.this, destResponse -> {
                                LatLng destLatLng = destResponse.getPlace().getLocation();
                                if (destLatLng == null) {
                                    setBusy(false);
                                    Toast.makeText(PathSelectionActivity.this, "Destination has no location.", Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                callRouteApi(
                                        sourceLatLng.latitude,
                                        sourceLatLng.longitude,
                                        destLatLng.latitude,
                                        destLatLng.longitude,
                                        totalTravelSeconds,
                                        context
                                );
                            })
                            .addOnFailureListener(PathSelectionActivity.this, e -> {
                                setBusy(false);
                                Toast.makeText(
                                        PathSelectionActivity.this,
                                        "Destination fetch failed: " + e.getMessage(),
                                        Toast.LENGTH_SHORT
                                ).show();
                            });
                })
                .addOnFailureListener(this, e -> {
                    setBusy(false);
                    Toast.makeText(
                            PathSelectionActivity.this,
                            "Source fetch failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT
                    ).show();
                });
    }

    private void callRouteApi(double originLat,
                              double originLon,
                              double destLat,
                              double destLon,
                              int totalTravelSeconds,
                              @NonNull String context) {

        try {
            JSONObject json = new JSONObject();

            JSONObject origin = new JSONObject();
            origin.put("lat", originLat);
            origin.put("lon", originLon);

            JSONObject destination = new JSONObject();
            destination.put("lat", destLat);
            destination.put("lon", destLon);

            json.put("source", origin);
            json.put("destination", destination);

            json.put("total_travel_time", totalTravelSeconds);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(ROUTE_URL)
                    .post(body);
            if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty()) {
                requestBuilder.header("X-App-Token", BuildConfig.SERVER_ACCESS_TOKEN);
            }
            Request request = requestBuilder.build();

            // Cancel prior call if user double-clicked etc.
            if (inFlightCall != null) {
                inFlightCall.cancel();
            }
            inFlightCall = httpClient.newCall(request);

            inFlightCall.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    runOnUiThread(() -> {
                        inFlightCall = null;
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        Toast.makeText(
                                PathSelectionActivity.this,
                                "API call failed: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    String responseBody = "";
                    try {
                        if (response.body() != null) {
                            responseBody = response.body().string();
                        }

                        final boolean ok = response.isSuccessful();
                        final int code = response.code();
                        final String bodyString = responseBody;

                        runOnUiThread(() -> {
                            inFlightCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            setBusy(false);

                            if (!ok) {
                                Toast.makeText(
                                        PathSelectionActivity.this,
                                        "Server error (" + code + "): " + safePreview(bodyString),
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }

                            // Persist body to cache to avoid Intent size limits
                            try {
                                String path = CacheFiles.writeText(
                                        PathSelectionActivity.this, "routes", "route", ".json", bodyString);

                                Intent intent = new Intent(PathSelectionActivity.this, RouteOptionsActivity.class);
                                intent.putExtra(EXTRA_ROUTE_JSON_PATH, path);
                                intent.putExtra(com.example.walking_meditation.nav.Extras.EXTRA_CONTEXT, context);
                                intent.putExtra(
                                        com.example.walking_meditation.nav.Extras.EXTRA_STUDY_PARAMETERS,
                                        participatingInStudy ? studyParameters : ""
                                );
                                intent.putExtra(com.example.walking_meditation.nav.Extras.EXTRA_TOTAL_TRAVEL_SECONDS, totalTravelSeconds);
                                startActivity(intent);
                            } catch (IOException ioe) {
                                Toast.makeText(
                                        PathSelectionActivity.this,
                                        "Failed to save response: " + ioe.getMessage(),
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        });

                    } catch (IOException e) {
                        runOnUiThread(() -> {
                            inFlightCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            setBusy(false);
                            Toast.makeText(
                                    PathSelectionActivity.this,
                                    "Response read failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            setBusy(false);
            Toast.makeText(
                    this,
                    "JSON build error: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String safePreview(@NonNull String s) {
        // Prevent huge toast spam; show a short preview.
        int max = 160;
        String trimmed = s.trim().replace("\n", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
