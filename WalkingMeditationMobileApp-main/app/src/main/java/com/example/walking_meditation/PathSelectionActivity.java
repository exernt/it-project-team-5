package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_PLAN_PATH;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.walking_meditation.adapters.RouteOptionsAdapter;
import com.example.walking_meditation.models.ApiResponse;
import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.models.ParkItem;
import com.example.walking_meditation.models.RouteOption;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.PolylineDecoder;
import com.example.walking_meditation.utils.RoutePlanFiles;
import com.example.walking_meditation.utils.SessionManager;
import com.example.walking_meditation.utils.StudyConfigClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.gson.Gson;

import org.json.JSONObject;
import org.json.JSONArray;

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

public class PathSelectionActivity extends AppCompatActivity
        implements RouteOptionsAdapter.OnRouteClickListener {

    private static final long AUTOCOMPLETE_DEBOUNCE_MS = 350;
    private static final LatLng DEFAULT_MAP_CENTRE = new LatLng(-37.8136, 144.9631);
    private static final String ROUTE_URL = BuildConfig.SERVER_BASE_URL + "/route/generate-path";
    private static final String SCRIPT_WITH_PARK_URL =
            BuildConfig.SERVER_BASE_URL + "/route/generate-script/park";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String STATE_SOURCE_PLACE_ID = "source_place_id";
    private static final String STATE_DESTINATION_PLACE_ID = "destination_place_id";
    private static final String STATE_SOURCE_LAT = "source_lat";
    private static final String STATE_SOURCE_LON = "source_lon";
    private static final String STATE_DEST_LAT = "destination_lat";
    private static final String STATE_DEST_LON = "destination_lon";
    private static final String STATE_HAS_SOURCE = "has_source";
    private static final String STATE_HAS_DESTINATION = "has_destination";
    private static final String STATE_RESULTS_VISIBLE = "results_visible";
    private static final String STATE_ROUTE_RESPONSE_PATH = "route_response_path";
    private static final String STATE_CONTEXT = "route_context";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AutocompletePrediction> sourcePredictions = new ArrayList<>();
    private final List<AutocompletePrediction> destinationPredictions = new ArrayList<>();
    private final List<RouteOption> routeOptions = new ArrayList<>();
    private final List<Polyline> displayedRouteLines = new ArrayList<>();
    private final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.MINUTES)
            .build();

    private PlacesClient placesClient;
    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private boolean useLocationAfterPermission;

    @Nullable private String selectedSourcePlaceId;
    @Nullable private String selectedDestinationPlaceId;
    @Nullable private LatLng selectedSourceLocation;
    @Nullable private LatLng selectedDestinationLocation;
    private boolean sourceSelectionInProgress;
    private boolean destinationSelectionInProgress;

    private AutoCompleteTextView etSource;
    private AutoCompleteTextView etDestination;
    private EditText etTotalMinutes;
    private EditText etContext;
    private Button btnUseCurrentLocation;
    private Button btnFindPath;
    private Button btnSelectRoute;
    private Button btnBackToSearch;
    private TextView tvStudyStatus;
    private TextView tvRoutesHeader;
    private LinearLayout inputControls;
    private LinearLayout resultsContainer;
    private RecyclerView routeOptionsView;
    private RouteOptionsAdapter routeOptionsAdapter;

    private MapView mapView;
    @Nullable private GoogleMap googleMap;
    @Nullable private Marker sourceMarker;
    @Nullable private Marker destinationMarker;
    @Nullable private Marker parkMarker;

    @Nullable private Call inFlightCall;
    @Nullable private ApiResponse routeResponse;
    @Nullable private RouteOption selectedRoute;
    @Nullable private String routeResponsePath;
    @Nullable private String selectedRoutePlanPath;
    private String lastMeditationContext = "";

    private boolean participatingInStudy;
    private String studyParameters = "";
    private String contextBeforeStudy = "";
    private boolean routeBusy;
    private boolean scriptBusy;
    private boolean locationBusy;
    private boolean studyRefreshPending;
    private boolean showingResults;
    private boolean launchedFromStudyManagement;
    private boolean mapsConfigured;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_path_selection);

        bindViews();
        mapsConfigured = !getString(R.string.google_maps_key).trim().isEmpty();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                            || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                    if (granted) {
                        enableMyLocationLayer();
                        requestCurrentLocation(useLocationAfterPermission);
                    } else if (useLocationAfterPermission) {
                        Toast.makeText(this,
                                "Location permission is required to use your current position.",
                                Toast.LENGTH_LONG).show();
                    }
                    useLocationAfterPermission = false;
                }
        );
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this::onMapReady);

        launchedFromStudyManagement = getIntent().getBooleanExtra(
                com.example.walking_meditation.nav.Extras.EXTRA_FROM_STUDY_MANAGE,
                false
        );
        applyStudyConfiguration(
                SessionManager.hasActiveStudy(this),
                SessionManager.getStudyParameters(this)
        );

        routeOptionsAdapter = new RouteOptionsAdapter(routeOptions, this);
        routeOptionsView.setLayoutManager(new LinearLayoutManager(this));
        routeOptionsView.setAdapter(routeOptionsAdapter);

        String mapsKey = getString(R.string.google_maps_key).trim();
        if (mapsKey.isEmpty()) {
            btnFindPath.setEnabled(false);
            btnUseCurrentLocation.setEnabled(false);
            Toast.makeText(this,
                    "Set MAPS_API_KEY in Gradle properties to enable route search.",
                    Toast.LENGTH_LONG).show();
        } else {
            if (!Places.isInitialized()) {
                Places.initializeWithNewPlacesApiEnabled(getApplicationContext(), mapsKey);
            }
            placesClient = Places.createClient(this);
            setupAutocomplete(etSource, true);
            setupAutocomplete(etDestination, false);
        }

        btnUseCurrentLocation.setOnClickListener(v -> requestLocationAccess(true));
        btnFindPath.setOnClickListener(v -> generateRoutes());
        btnBackToSearch.setOnClickListener(v -> showInputState());
        btnSelectRoute.setOnClickListener(v -> generateSelectedRouteScript());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (scriptBusy) {
                    Toast.makeText(PathSelectionActivity.this,
                            "Please wait for script generation to finish.",
                            Toast.LENGTH_SHORT).show();
                } else if (showingResults) {
                    showInputState();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

    }

    private void bindViews() {
        etSource = findViewById(R.id.et_source);
        etDestination = findViewById(R.id.et_destination);
        etTotalMinutes = findViewById(R.id.et_total_minutes);
        etContext = findViewById(R.id.et_context);
        btnUseCurrentLocation = findViewById(R.id.btn_use_current_location);
        btnFindPath = findViewById(R.id.btn_find_path);
        btnSelectRoute = findViewById(R.id.btn_select_route);
        btnBackToSearch = findViewById(R.id.btn_back_to_search);
        tvStudyStatus = findViewById(R.id.tv_study_status);
        tvRoutesHeader = findViewById(R.id.tv_routes_header);
        inputControls = findViewById(R.id.input_controls);
        resultsContainer = findViewById(R.id.results_container);
        routeOptionsView = findViewById(R.id.rv_route_options);
        mapView = findViewById(R.id.route_map);
    }

    private void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(true);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_MAP_CENTRE, 12f));
        enableMyLocationLayer();
        updateEndpointMarkers(selectedSourceLocation != null || selectedDestinationLocation != null);

        if (!mapsConfigured) return;

        if (!showingResults && selectedSourceLocation == null
                && selectedDestinationLocation == null && hasLocationPermission()) {
            requestCurrentLocation(false);
        } else if (!showingResults && selectedSourceLocation == null
                && selectedDestinationLocation == null) {
            requestLocationAccess(false);
        }
    }

    private void requestLocationAccess(boolean setAsSource) {
        if (!mapsConfigured) return;
        if (hasLocationPermission()) {
            requestCurrentLocation(setAsSource);
            return;
        }
        useLocationAfterPermission = setAsSource;
        locationPermissionLauncher.launch(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enableMyLocationLayer() {
        if (googleMap == null || !hasLocationPermission()) return;
        try {
            googleMap.setMyLocationEnabled(true);
        } catch (SecurityException ignored) {
            // Permission can be revoked between the check and SDK call.
        }
    }

    private void requestCurrentLocation(boolean setAsSource) {
        if (!hasLocationPermission() || locationBusy) return;
        locationBusy = true;
        if (setAsSource) {
            btnUseCurrentLocation.setText("Locating...");
            btnUseCurrentLocation.setEnabled(false);
        }

        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        try {
            fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            cancellationTokenSource.getToken())
                    .addOnSuccessListener(this, location -> handleCurrentLocation(location, setAsSource))
                    .addOnFailureListener(this, exception -> {
                        finishLocationRequest(setAsSource);
                        Toast.makeText(this,
                                "Unable to determine current location: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
        } catch (SecurityException exception) {
            finishLocationRequest(setAsSource);
            requestLocationAccess(setAsSource);
        }
    }

    private void handleCurrentLocation(@Nullable Location location, boolean setAsSource) {
        finishLocationRequest(setAsSource);
        if (location == null) {
            if (setAsSource) {
                Toast.makeText(this,
                        "Current location is unavailable. Please try again.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        LatLng current = new LatLng(location.getLatitude(), location.getLongitude());
        if (googleMap != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 15f));
        }

        if (setAsSource) {
            selectedSourcePlaceId = null;
            selectedSourceLocation = current;
            sourceSelectionInProgress = true;
            etSource.setText("Current location", false);
            sourceSelectionInProgress = false;
            updateEndpointMarkers(true);
        }
    }

    private void finishLocationRequest(boolean setAsSource) {
        locationBusy = false;
        if (setAsSource) {
            btnUseCurrentLocation.setText("Use current location");
            btnUseCurrentLocation.setEnabled(
                    mapsConfigured && !showingResults && !routeBusy);
        }
    }

    private void setupAutocomplete(AutoCompleteTextView textView, boolean source) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>()
        );
        textView.setAdapter(adapter);
        textView.setThreshold(1);

        textView.setOnItemClickListener((parent, view, position, id) -> {
            List<AutocompletePrediction> predictions =
                    source ? sourcePredictions : destinationPredictions;
            if (position < 0 || position >= predictions.size()) return;

            AutocompletePrediction prediction = predictions.get(position);
            if (source) {
                sourceSelectionInProgress = true;
                selectedSourcePlaceId = prediction.getPlaceId();
                selectedSourceLocation = null;
                textView.setText(prediction.getFullText(null).toString(), false);
                sourceSelectionInProgress = false;
            } else {
                destinationSelectionInProgress = true;
                selectedDestinationPlaceId = prediction.getPlaceId();
                selectedDestinationLocation = null;
                textView.setText(prediction.getFullText(null).toString(), false);
                destinationSelectionInProgress = false;
            }
            fetchSelectedPlaceLocation(prediction.getPlaceId(), source);
        });

        final Runnable[] pending = new Runnable[1];
        textView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean programmaticSelection = source
                        ? sourceSelectionInProgress : destinationSelectionInProgress;
                if (programmaticSelection) {
                    if (pending[0] != null) mainHandler.removeCallbacks(pending[0]);
                    return;
                }

                if (source) {
                    selectedSourcePlaceId = null;
                    selectedSourceLocation = null;
                    updateEndpointMarkers(false);
                } else {
                    selectedDestinationPlaceId = null;
                    selectedDestinationLocation = null;
                    updateEndpointMarkers(false);
                }

                if (pending[0] != null) mainHandler.removeCallbacks(pending[0]);
                String query = s.toString().trim();
                if (query.length() < 2) {
                    adapter.clear();
                    if (source) sourcePredictions.clear(); else destinationPredictions.clear();
                    return;
                }

                pending[0] = () -> requestPredictions(query, textView, adapter, source);
                mainHandler.postDelayed(pending[0], AUTOCOMPLETE_DEBOUNCE_MS);
            }

            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void requestPredictions(
            @NonNull String query,
            @NonNull AutoCompleteTextView textView,
            @NonNull ArrayAdapter<String> adapter,
            boolean source
    ) {
        if (placesClient == null) return;
        FindAutocompletePredictionsRequest request =
                FindAutocompletePredictionsRequest.builder().setQuery(query).build();

        placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener(this, response -> {
                    if (!query.equals(textView.getText().toString().trim())) return;
                    List<AutocompletePrediction> predictions = response.getAutocompletePredictions();
                    List<AutocompletePrediction> target =
                            source ? sourcePredictions : destinationPredictions;
                    target.clear();
                    target.addAll(predictions);

                    List<String> suggestions = new ArrayList<>(predictions.size());
                    for (AutocompletePrediction prediction : predictions) {
                        suggestions.add(prediction.getFullText(null).toString());
                    }
                    adapter.clear();
                    adapter.addAll(suggestions);
                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(this, exception -> {
                    if (!query.equals(textView.getText().toString().trim())) return;
                    if (source) sourcePredictions.clear(); else destinationPredictions.clear();
                    adapter.clear();
                    Toast.makeText(this,
                            "Autocomplete failed: " + exception.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void fetchSelectedPlaceLocation(@NonNull String placeId, boolean source) {
        if (placesClient == null) return;
        FetchPlaceRequest request = FetchPlaceRequest.newInstance(
                placeId,
                List.of(Place.Field.LOCATION)
        );
        placesClient.fetchPlace(request)
                .addOnSuccessListener(this, response -> {
                    LatLng location = response.getPlace().getLocation();
                    if (location == null) {
                        Toast.makeText(this, "Selected place has no location.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (source && placeId.equals(selectedSourcePlaceId)) {
                        selectedSourceLocation = location;
                    } else if (!source && placeId.equals(selectedDestinationPlaceId)) {
                        selectedDestinationLocation = location;
                    } else {
                        return;
                    }
                    updateEndpointMarkers(true);
                })
                .addOnFailureListener(this, exception -> Toast.makeText(this,
                        "Unable to resolve selected place: " + exception.getMessage(),
                        Toast.LENGTH_LONG).show());
    }

    private void updateEndpointMarkers(boolean moveCamera) {
        if (googleMap == null) return;

        if (sourceMarker != null) sourceMarker.remove();
        if (destinationMarker != null) destinationMarker.remove();
        sourceMarker = null;
        destinationMarker = null;

        if (selectedSourceLocation != null) {
            sourceMarker = googleMap.addMarker(new MarkerOptions()
                    .position(selectedSourceLocation)
                    .title("Start"));
        }
        if (selectedDestinationLocation != null) {
            destinationMarker = googleMap.addMarker(new MarkerOptions()
                    .position(selectedDestinationLocation)
                    .title("Destination"));
        }

        if (!moveCamera) return;
        if (selectedSourceLocation != null && selectedDestinationLocation != null) {
            LatLngBounds bounds = new LatLngBounds.Builder()
                    .include(selectedSourceLocation)
                    .include(selectedDestinationLocation)
                    .build();
            mapView.post(() -> {
                if (googleMap != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80));
                }
            });
        } else if (selectedSourceLocation != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedSourceLocation, 15f));
        } else if (selectedDestinationLocation != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedDestinationLocation, 15f));
        }
    }

    private void generateRoutes() {
        if (selectedSourceLocation == null || selectedDestinationLocation == null) {
            Toast.makeText(this,
                    "Select both a starting point and destination first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int totalMinutes;
        try {
            totalMinutes = Integer.parseInt(etTotalMinutes.getText().toString().trim());
        } catch (NumberFormatException exception) {
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

        lastMeditationContext = context;
        setRouteBusy(true);
        callRouteApi(selectedSourceLocation, selectedDestinationLocation, totalMinutes * 60);
    }

    private void callRouteApi(
            @NonNull LatLng source,
            @NonNull LatLng destination,
            int totalTravelSeconds
    ) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("source", latLngJson(source.latitude, source.longitude));
            payload.put("destination", latLngJson(destination.latitude, destination.longitude));
            payload.put("total_travel_time", totalTravelSeconds);

            Request request = authenticatedRequest(ROUTE_URL)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            enqueueRequest(request, false);
        } catch (Exception exception) {
            setRouteBusy(false);
            Toast.makeText(this,
                    "Unable to build route request: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void enqueueRequest(@NonNull Request request, boolean scriptRequest) {
        if (inFlightCall != null) inFlightCall.cancel();
        inFlightCall = httpClient.newCall(request);
        inFlightCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                runOnUiThread(() -> {
                    if (call.isCanceled() || isFinishing() || isDestroyed()) return;
                    inFlightCall = null;
                    if (scriptRequest) setScriptBusy(false); else setRouteBusy(false);
                    Toast.makeText(PathSelectionActivity.this,
                            (scriptRequest ? "Script" : "Route")
                                    + " request failed: " + exception.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body;
                boolean successful;
                int statusCode;
                try (Response closedResponse = response) {
                    body = closedResponse.body() == null ? "" : closedResponse.body().string();
                    successful = closedResponse.isSuccessful();
                    statusCode = closedResponse.code();
                }

                String responseBody = body;
                boolean ok = successful;
                int code = statusCode;
                runOnUiThread(() -> {
                    inFlightCall = null;
                    if (isFinishing() || isDestroyed()) return;
                    if (scriptRequest) setScriptBusy(false); else setRouteBusy(false);

                    if (!ok) {
                        Toast.makeText(PathSelectionActivity.this,
                                "Server error (" + code + "): " + safePreview(responseBody),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (scriptRequest) {
                        openScriptResponse(responseBody);
                    } else {
                        handleRouteResponse(responseBody);
                    }
                });
            }
        });
    }

    private void handleRouteResponse(@NonNull String responseBody) {
        try {
            ApiResponse parsed = gson.fromJson(responseBody, ApiResponse.class);
            if (parsed == null || parsed.parks == null || parsed.parks.isEmpty()) {
                String message = parsed != null && parsed.baseline != null
                        && parsed.total_travel_time != null
                        && parsed.baseline.duration_s > parsed.total_travel_time
                        ? "The direct walk exceeds the selected time limit."
                        : "No route through an eligible urban park fits the selected time.";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                return;
            }

            routeResponse = parsed;
            routeOptionsAdapter.clearSelection();
            routeOptions.clear();
            int count = Math.min(3, parsed.parks.size());
            for (int index = 0; index < count; index++) {
                routeOptions.add(RouteOption.fromPark(parsed.parks.get(index)));
            }
            routeOptionsAdapter.notifyDataSetChanged();
            routeResponsePath = CacheFiles.writeText(
                    this, "routes", "route", ".json", responseBody);
            showResultsState();
        } catch (Exception exception) {
            Toast.makeText(this,
                    "Invalid route response: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showResultsState() {
        showingResults = true;
        selectedRoute = null;
        inputControls.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);
        setEndpointEditingEnabled(false);
        routeOptionsAdapter.clearSelection();
        btnSelectRoute.setEnabled(false);
        btnSelectRoute.setText("Select Route");
        tvRoutesHeader.setText(String.format(Locale.US,
                "Choose a route (%d option%s)",
                routeOptions.size(),
                routeOptions.size() == 1 ? "" : "s"));
        clearDisplayedRoute();
        updateEndpointMarkers(true);
    }

    private void showInputState() {
        showingResults = false;
        selectedRoute = null;
        resultsContainer.setVisibility(View.GONE);
        inputControls.setVisibility(View.VISIBLE);
        setEndpointEditingEnabled(true);
        btnSelectRoute.setEnabled(false);
        routeOptionsAdapter.clearSelection();
        clearDisplayedRoute();
        updateEndpointMarkers(true);
        applyStudyConfiguration(participatingInStudy, studyParameters);
        updateFindPathEnabled();
    }

    private void setEndpointEditingEnabled(boolean enabled) {
        etSource.setEnabled(enabled);
        etDestination.setEnabled(enabled);
        btnUseCurrentLocation.setEnabled(
                mapsConfigured && enabled && !locationBusy && !routeBusy);
    }

    @Override
    public void onRouteClicked(RouteOption option) {
        if (scriptBusy) return;
        selectedRoute = option;
        btnSelectRoute.setEnabled(true);
        displaySelectedRoute(option);
    }

    private void displaySelectedRoute(@NonNull RouteOption option) {
        clearDisplayedRoute();
        if (googleMap == null) return;

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        boolean hasBounds = false;
        if (selectedSourceLocation != null) {
            bounds.include(selectedSourceLocation);
            hasBounds = true;
        }
        if (selectedDestinationLocation != null) {
            bounds.include(selectedDestinationLocation);
            hasBounds = true;
        }

        try {
            for (String encoded : new String[] {option.polyline, option.polylineFromPark}) {
                if (encoded == null || encoded.isEmpty()) continue;
                PolylineOptions lineOptions = new PolylineOptions()
                        .color(ContextCompat.getColor(this, R.color.sky_blue_dark))
                        .width(10f);
                for (LatLon point : PolylineDecoder.decode(encoded)) {
                    LatLng mapPoint = new LatLng(point.lat, point.lon);
                    lineOptions.add(mapPoint);
                    bounds.include(mapPoint);
                    hasBounds = true;
                }
                displayedRouteLines.add(googleMap.addPolyline(lineOptions));
            }
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, "The selected route geometry is invalid.", Toast.LENGTH_LONG).show();
            return;
        }

        ParkItem park = findParkItem(option.parkPlaceId);
        if (park != null && park.park != null) {
            LatLng parkLocation = new LatLng(park.park.lat, park.park.lon);
            parkMarker = googleMap.addMarker(new MarkerOptions()
                    .position(parkLocation)
                    .title(park.park.name));
            bounds.include(parkLocation);
            hasBounds = true;
        }

        if (hasBounds) {
            LatLngBounds routeBounds = bounds.build();
            mapView.post(() -> {
                if (googleMap != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(routeBounds, 80));
                }
            });
        }
    }

    private void clearDisplayedRoute() {
        for (Polyline line : displayedRouteLines) line.remove();
        displayedRouteLines.clear();
        if (parkMarker != null) parkMarker.remove();
        parkMarker = null;
    }

    private void generateSelectedRouteScript() {
        if (selectedRoute == null || routeResponse == null
                || routeResponse.origin == null || routeResponse.destination == null) {
            Toast.makeText(this, "Select a route first.", Toast.LENGTH_SHORT).show();
            return;
        }

        ParkItem parkItem = findParkItem(selectedRoute.parkPlaceId);
        if (parkItem == null || parkItem.park == null) {
            Toast.makeText(this, "Selected route has no park information.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("source", latLngJson(routeResponse.origin.lat, routeResponse.origin.lon));
            payload.put("destination", latLngJson(
                    routeResponse.destination.lat,
                    routeResponse.destination.lon));
            payload.put("park", latLngJson(parkItem.park.lat, parkItem.park.lon));
            payload.put("to_park_time", parkItem.to_park_s);
            payload.put("park_time", 0);
            payload.put("park_to_destination_time", parkItem.park_to_destination_s);
            payload.put("context", lastMeditationContext.isEmpty()
                    ? "general wellbeing" : lastMeditationContext);
            payload.put("study_parameters", participatingInStudy ? studyParameters : "");
            payload.put("source_to_park_steps", new JSONArray(
                    gson.toJson(parkItem.steps_to_park == null
                            ? new ArrayList<>() : parkItem.steps_to_park)));
            payload.put("park_to_destination_steps", new JSONArray(
                    gson.toJson(parkItem.steps_from_park == null
                            ? new ArrayList<>() : parkItem.steps_from_park)));

            RoutePlan routePlan = RoutePlan.forParkRoute(routeResponse, parkItem);
            selectedRoutePlanPath = RoutePlanFiles.save(this, routePlan);

            Request request = authenticatedRequest(SCRIPT_WITH_PARK_URL)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            setScriptBusy(true);
            enqueueRequest(request, true);
        } catch (Exception exception) {
            setScriptBusy(false);
            Toast.makeText(this,
                    "Unable to build script request: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openScriptResponse(@NonNull String responseBody) {
        try {
            String path = CacheFiles.writeText(
                    this, "scripts", "script-response", ".json", responseBody);
            Intent intent = new Intent(this, ScriptResultActivity.class);
            intent.putExtra(EXTRA_SCRIPT_JSON_PATH, path);
            intent.putExtra(EXTRA_ROUTE_PLAN_PATH, selectedRoutePlanPath);
            startActivity(intent);
        } catch (IOException exception) {
            Toast.makeText(this,
                    "Unable to save script response: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private ParkItem findParkItem(@Nullable String placeId) {
        if (placeId == null || routeResponse == null || routeResponse.parks == null) return null;
        for (ParkItem park : routeResponse.parks) {
            if (park != null && park.park != null && placeId.equals(park.park.place_id)) {
                return park;
            }
        }
        return null;
    }

    private Request.Builder authenticatedRequest(@NonNull String url) {
        Request.Builder builder = new Request.Builder().url(url);
        if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty()) {
            builder.header("X-App-Token", BuildConfig.SERVER_ACCESS_TOKEN);
        }
        return builder;
    }

    private static JSONObject latLngJson(double latitude, double longitude) throws Exception {
        JSONObject value = new JSONObject();
        value.put("lat", latitude);
        value.put("lon", longitude);
        return value;
    }

    private void setRouteBusy(boolean busy) {
        routeBusy = busy;
        btnFindPath.setText(busy ? "Loading..." : "Generate routes");
        updateFindPathEnabled();
        btnUseCurrentLocation.setEnabled(
                mapsConfigured && !busy && !locationBusy && !showingResults);
    }

    private void setScriptBusy(boolean busy) {
        scriptBusy = busy;
        btnSelectRoute.setText(busy ? "Loading..." : "Select Route");
        btnSelectRoute.setEnabled(!busy && selectedRoute != null);
        btnBackToSearch.setEnabled(!busy);
        routeOptionsView.setEnabled(!busy);
    }

    private void updateFindPathEnabled() {
        btnFindPath.setEnabled(mapsConfigured && !routeBusy && !studyRefreshPending);
    }

    private void refreshStudyConfiguration() {
        if (studyRefreshPending || showingResults) return;
        studyRefreshPending = true;
        updateFindPathEnabled();

        StudyConfigClient.fetchCurrent(this, httpClient, new StudyConfigClient.Listener() {
            @Override
            public void onLoaded(boolean participating, @NonNull String studyId,
                                 @NonNull String parameters) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    studyRefreshPending = false;
                    if (participating) {
                        SessionManager.cacheStudyConfiguration(
                                PathSelectionActivity.this, studyId, parameters);
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
                    Toast.makeText(PathSelectionActivity.this,
                            "Using cached study settings: " + message,
                            Toast.LENGTH_SHORT).show();
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

    private void restoreScreenState(@Nullable Bundle state) {
        if (state == null) return;
        selectedSourcePlaceId = state.getString(STATE_SOURCE_PLACE_ID);
        selectedDestinationPlaceId = state.getString(STATE_DESTINATION_PLACE_ID);
        if (state.getBoolean(STATE_HAS_SOURCE)) {
            selectedSourceLocation = new LatLng(
                    state.getDouble(STATE_SOURCE_LAT), state.getDouble(STATE_SOURCE_LON));
        }
        if (state.getBoolean(STATE_HAS_DESTINATION)) {
            selectedDestinationLocation = new LatLng(
                    state.getDouble(STATE_DEST_LAT), state.getDouble(STATE_DEST_LON));
        }
        lastMeditationContext = state.getString(STATE_CONTEXT, "");
        routeResponsePath = state.getString(STATE_ROUTE_RESPONSE_PATH);

        if (state.getBoolean(STATE_RESULTS_VISIBLE) && routeResponsePath != null) {
            try {
                String json = CacheFiles.readText(routeResponsePath);
                routeResponse = gson.fromJson(json, ApiResponse.class);
                if (routeResponse != null && routeResponse.parks != null) {
                    routeOptions.clear();
                    int count = Math.min(3, routeResponse.parks.size());
                    for (int index = 0; index < count; index++) {
                        routeOptions.add(RouteOption.fromPark(routeResponse.parks.get(index)));
                    }
                    routeOptionsAdapter.notifyDataSetChanged();
                    showResultsState();
                }
            } catch (Exception ignored) {
                showInputState();
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        restoreScreenState(savedInstanceState);
        updateEndpointMarkers(true);
    }

    private String safePreview(@NonNull String value) {
        String trimmed = value.trim().replace("\n", " ");
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160) + "…";
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(STATE_SOURCE_PLACE_ID, selectedSourcePlaceId);
        outState.putString(STATE_DESTINATION_PLACE_ID, selectedDestinationPlaceId);
        outState.putBoolean(STATE_HAS_SOURCE, selectedSourceLocation != null);
        outState.putBoolean(STATE_HAS_DESTINATION, selectedDestinationLocation != null);
        if (selectedSourceLocation != null) {
            outState.putDouble(STATE_SOURCE_LAT, selectedSourceLocation.latitude);
            outState.putDouble(STATE_SOURCE_LON, selectedSourceLocation.longitude);
        }
        if (selectedDestinationLocation != null) {
            outState.putDouble(STATE_DEST_LAT, selectedDestinationLocation.latitude);
            outState.putDouble(STATE_DEST_LON, selectedDestinationLocation.longitude);
        }
        outState.putBoolean(STATE_RESULTS_VISIBLE, showingResults);
        outState.putString(STATE_ROUTE_RESPONSE_PATH, routeResponsePath);
        outState.putString(STATE_CONTEXT, lastMeditationContext);
        mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (launchedFromStudyManagement && SessionManager.hasActiveStudy(this)) {
            applyStudyConfiguration(true, SessionManager.getStudyParameters(this));
        } else {
            refreshStudyConfiguration();
        }
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (inFlightCall != null) inFlightCall.cancel();
        clearDisplayedRoute();
        mapView.onDestroy();
        super.onDestroy();
    }
}
