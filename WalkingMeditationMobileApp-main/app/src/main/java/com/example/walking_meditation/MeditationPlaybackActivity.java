package com.example.walking_meditation;

import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_PLAN_PATH;
import static com.example.walking_meditation.nav.Extras.EXTRA_SCRIPT_JSON_PATH;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.models.RouteLeg;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.RouteStep;
import com.example.walking_meditation.navigation.RouteProgressTracker;
import com.example.walking_meditation.utils.CacheFiles;
import com.example.walking_meditation.utils.RoutePlanFiles;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PatternItem;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Full-screen selected-route navigation with deterministic simulated narration events. */
public class MeditationPlaybackActivity extends AppCompatActivity {
    private static final double SEGMENT_LEAD_METRES = 25.0;
    private static final long LOCATION_INTERVAL_MS = 1_000L;

    private static final class PlaybackSegment {
        String id;
        double routeStartM;
        long plannedStartMs;
    }

    private MapView mapView;
    @Nullable private GoogleMap googleMap;
    private TextView instructionView;
    private TextView routeStatusView;
    private TextView playbackLogView;
    private Button pauseButton;
    private Button recenterButton;

    private FusedLocationProviderClient locationClient;
    @Nullable private LocationCallback locationCallback;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private RoutePlan routePlan;
    private RouteProgressTracker progressTracker;
    private List<RouteStep> routeSteps = new ArrayList<>();
    private final List<PlaybackSegment> playbackSegments = new ArrayList<>();
    private final List<String> playbackLog = new ArrayList<>();

    @Nullable private Polyline completedLine;
    @Nullable private Polyline remainingLine;
    @Nullable private Polyline recoveryLine;
    @Nullable private Location previousLocation;

    private boolean walkPaused;
    private boolean followUser = true;
    private int navigationStepIndex;
    private int playbackSegmentIndex;
    private long activeWalkingMs;
    private long lastLocationRealtimeMs;
    @Nullable private String routePlanPath;
    @Nullable private String scriptJsonPath;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meditation_playback);

        mapView = findViewById(R.id.navigation_map);
        instructionView = findViewById(R.id.tv_navigation_instruction);
        routeStatusView = findViewById(R.id.tv_route_status);
        playbackLogView = findViewById(R.id.tv_playback_log);
        pauseButton = findViewById(R.id.btn_pause_walk);
        recenterButton = findViewById(R.id.btn_recenter);
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = Boolean.TRUE.equals(
                            result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                            || Boolean.TRUE.equals(
                            result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                    if (granted) {
                        enableLocationLayer();
                        if (!walkPaused) startLocationUpdates();
                    } else {
                        routeStatusView.setText("Location permission is required for navigation");
                    }
                }
        );

        try {
            loadRouteAndScript();
            progressTracker = new RouteProgressTracker(routePlan);
            routeSteps = routePlan.allSteps();
            buildPlaybackSchedule();
        } catch (Exception exception) {
            Toast.makeText(this,
                    "Unable to start navigation: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pauseButton.setOnClickListener(view -> setWalkPaused(!walkPaused));
        recenterButton.setOnClickListener(view -> {
            followUser = true;
            recenterButton.setVisibility(View.GONE);
            if (previousLocation != null) followLocation(previousLocation);
        });

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this::onMapReady);
    }

    private void loadRouteAndScript() throws IOException {
        routePlanPath = getIntent().getStringExtra(EXTRA_ROUTE_PLAN_PATH);
        scriptJsonPath = getIntent().getStringExtra(EXTRA_SCRIPT_JSON_PATH);
        if (routePlanPath == null || routePlanPath.trim().isEmpty()) {
            throw new IOException("Missing selected route plan");
        }
        if (scriptJsonPath == null || scriptJsonPath.trim().isEmpty()) {
            throw new IOException("Missing generated script");
        }
        routePlan = RoutePlanFiles.read(this, routePlanPath);
        parseScriptSegments(CacheFiles.readText(scriptJsonPath));
    }

    private void parseScriptSegments(String responseJson) throws IOException {
        try {
            JSONObject response = new JSONObject(responseJson);
            JSONObject script = response.optJSONObject("script");
            if (script == null) throw new IOException("Script response has no script object");
            JSONArray sections = script.optJSONArray("sections");
            if (sections == null) throw new IOException("Script response has no sections");

            Map<String, RouteLeg> legsById = new HashMap<>();
            for (RouteLeg leg : routePlan.legs) {
                if (leg.steps == null) leg.steps = new ArrayList<>();
                legsById.put(leg.id, leg);
            }

            for (int sectionIndex = 0; sectionIndex < sections.length(); sectionIndex++) {
                JSONObject section = sections.optJSONObject(sectionIndex);
                if (section == null) continue;
                JSONArray segments = section.optJSONArray("route_segments");
                if (segments == null) continue;
                for (int index = 0; index < segments.length(); index++) {
                    JSONObject segment = segments.optJSONObject(index);
                    if (segment == null) continue;
                    String id = segment.optString("id", "").trim();
                    if (id.isEmpty()) continue;
                    PlaybackSegment playback = new PlaybackSegment();
                    playback.id = id;
                    playbackSegments.add(playback);

                    String phase = segment.optString("route_phase", "");
                    RouteLeg leg = legsById.get(phase);
                    if (leg != null && findStep(leg.steps, id) == null) {
                        RouteStep step = routeStepFromJson(segment, id);
                        leg.steps.add(step);
                    }
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Invalid script response", exception);
        }
    }

    private static RouteStep routeStepFromJson(JSONObject value, String id) {
        RouteStep step = new RouteStep();
        step.id = id;
        step.index = value.optInt("index", 1);
        step.instruction = value.optString("instruction", "Continue along the highlighted route");
        step.duration_s = value.optInt("duration_s", 0);
        step.distance_m = value.optInt("distance_m", 0);
        step.polyline = value.optString("polyline", "");
        step.start = latLon(value.optJSONObject("start"));
        step.end = latLon(value.optJSONObject("end"));
        return step;
    }

    private static LatLon latLon(@Nullable JSONObject value) {
        if (value == null) return null;
        return new LatLon(value.optDouble("lat"), value.optDouble("lon"));
    }

    @Nullable
    private static RouteStep findStep(List<RouteStep> steps, String id) {
        for (RouteStep step : steps) {
            if (id.equals(step.id)) return step;
        }
        return null;
    }

    private void buildPlaybackSchedule() {
        Map<String, RouteStep> stepsById = new HashMap<>();
        long plannedStartMs = 0;
        for (RouteStep step : routeSteps) {
            stepsById.put(step.id, step);
        }
        for (PlaybackSegment playback : playbackSegments) {
            RouteStep step = stepsById.get(playback.id);
            if (step != null) {
                playback.routeStartM = step.routeStartDistanceM;
                playback.plannedStartMs = plannedStartMs;
                plannedStartMs += Math.max(1, step.duration_s) * 1_000L;
            }
        }
    }

    private void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.getUiSettings().setZoomGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followUser = false;
                recenterButton.setVisibility(View.VISIBLE);
            }
        });

        List<LatLng> points = progressTracker.getRoutePoints();
        remainingLine = map.addPolyline(new PolylineOptions()
                .addAll(points)
                .color(ContextCompat.getColor(this, R.color.sky_blue_dark))
                .width(14f)
                .zIndex(2f));
        completedLine = map.addPolyline(new PolylineOptions()
                .color(ContextCompat.getColor(this, R.color.disabled_grey))
                .width(12f)
                .zIndex(1f));

        map.addMarker(new MarkerOptions()
                .position(new LatLng(routePlan.destination.lat, routePlan.destination.lon))
                .title("Destination"));
        if (routePlan.park != null) {
            map.addMarker(new MarkerOptions()
                    .position(new LatLng(routePlan.park.lat, routePlan.park.lon))
                    .title("Park waypoint"));
        }
        showWholeRoute(points);
        enableLocationLayer();
        requestLocationPermission();
    }

    private void showWholeRoute(List<LatLng> points) {
        if (googleMap == null || points.isEmpty()) return;
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (LatLng point : points) bounds.include(point);
        mapView.post(() -> {
            if (googleMap != null) {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100));
            }
        });
    }

    private void requestLocationPermission() {
        if (hasLocationPermission()) {
            enableLocationLayer();
            if (!walkPaused) startLocationUpdates();
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enableLocationLayer() {
        if (googleMap == null || !hasLocationPermission()) return;
        try {
            googleMap.setMyLocationEnabled(true);
        } catch (SecurityException ignored) {
            // Permission can change between the explicit check and this call.
        }
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission() || locationCallback != null || walkPaused) return;
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_INTERVAL_MS
        ).setMinUpdateIntervalMillis(750L).build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location location : result.getLocations()) handleLocation(location);
            }
        };
        try {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {
            routeStatusView.setText("Location permission is unavailable");
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback == null) return;
        locationClient.removeLocationUpdates(locationCallback);
        locationCallback = null;
        lastLocationRealtimeMs = 0;
    }

    private void handleLocation(@NonNull Location location) {
        if (walkPaused || location.getAccuracy() > 75f) return;
        updateActiveWalkingTime(location);
        previousLocation = new Location(location);
        RouteProgressTracker.Match match = progressTracker.update(location);
        updateRouteLines(match);
        updateNavigationInstruction(match, location);
        updatePlayback(match);
        if (followUser) followLocation(location);
    }

    private void updateActiveWalkingTime(Location location) {
        long now = location.getElapsedRealtimeNanos() / 1_000_000L;
        if (lastLocationRealtimeMs > 0) {
            long delta = Math.min(5_000L, Math.max(0, now - lastLocationRealtimeMs));
            boolean moving = location.hasSpeed() && location.getSpeed() >= 0.2f;
            if (!moving && previousLocation != null) {
                moving = previousLocation.distanceTo(location) >= 1.5f;
            }
            if (moving) activeWalkingMs += delta;
        }
        lastLocationRealtimeMs = now;
    }

    private void updateRouteLines(RouteProgressTracker.Match match) {
        if (completedLine != null) completedLine.setPoints(progressTracker.completedPoints(match));
        if (remainingLine != null) remainingLine.setPoints(progressTracker.remainingPoints(match));
    }

    private void updateNavigationInstruction(
            RouteProgressTracker.Match match,
            Location location
    ) {
        if (match.offRoute) {
            instructionView.setText("Return to the highlighted route");
            float[] distance = new float[1];
            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(),
                    match.rejoinPoint.latitude,
                    match.rejoinPoint.longitude,
                    distance
            );
            routeStatusView.setText(String.format(
                    Locale.getDefault(),
                    "Off route • rejoin point %.0f m away",
                    distance[0]
            ));
            showRecoveryLine(new LatLng(location.getLatitude(), location.getLongitude()),
                    match.rejoinPoint);
            return;
        }

        clearRecoveryLine();
        while (navigationStepIndex < routeSteps.size() - 1) {
            RouteStep current = routeSteps.get(navigationStepIndex);
            double allowance = Math.max(10.0,
                    Math.min(30.0, (current.routeEndDistanceM - current.routeStartDistanceM) * 0.15));
            if (match.progressM < current.routeEndDistanceM - allowance) break;
            navigationStepIndex++;
        }

        if (routeSteps.isEmpty()) {
            instructionView.setText("Follow the highlighted route");
        } else {
            String instruction = routeSteps.get(navigationStepIndex).instruction;
            instructionView.setText(instruction == null || instruction.trim().isEmpty()
                    ? "Continue along the highlighted route" : instruction);
        }
        routeStatusView.setText(String.format(
                Locale.getDefault(),
                "On route • %.0f m remaining",
                Math.max(0, progressTracker.getTotalDistanceM() - match.progressM)
        ));
    }

    private void showRecoveryLine(LatLng current, LatLng rejoin) {
        if (googleMap == null) return;
        if (recoveryLine == null) {
            List<PatternItem> pattern = Arrays.asList(new Dash(24f), new Gap(16f));
            recoveryLine = googleMap.addPolyline(new PolylineOptions()
                    .color(0xFFFF8F00)
                    .width(10f)
                    .pattern(pattern)
                    .zIndex(3f));
        }
        recoveryLine.setPoints(Arrays.asList(current, rejoin));
        recoveryLine.setVisible(true);
    }

    private void clearRecoveryLine() {
        if (recoveryLine != null) recoveryLine.setVisible(false);
    }

    private void updatePlayback(RouteProgressTracker.Match match) {
        if (playbackSegmentIndex >= playbackSegments.size()) return;
        PlaybackSegment next = playbackSegments.get(playbackSegmentIndex);
        boolean reachedSpatialWindow = !match.offRoute
                && match.progressM + SEGMENT_LEAD_METRES >= next.routeStartM;
        boolean reachedTimeFallback = activeWalkingMs >= next.plannedStartMs + 60_000L;
        if (!reachedSpatialWindow && !reachedTimeFallback) return;

        String message = "Playing segment " + next.id;
        playbackLog.add(message);
        while (playbackLog.size() > 4) playbackLog.remove(0);
        playbackLogView.setText(String.join("\n", playbackLog));
        playbackSegmentIndex++;
    }

    private void followLocation(Location location) {
        if (googleMap == null) return;
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(location.getLatitude(), location.getLongitude()),
                18f
        ));
    }

    private void setWalkPaused(boolean paused) {
        walkPaused = paused;
        pauseButton.setText(paused ? "Resume" : "Pause");
        if (paused) {
            stopLocationUpdates();
            routeStatusView.setText("Walk paused");
        } else {
            routeStatusView.setText("Resuming guidance…");
            startLocationUpdates();
        }
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
        if (!walkPaused && googleMap != null) requestLocationPermission();
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        mapView.onDestroy();
        if (isFinishing() && !isChangingConfigurations()) {
            if (routePlanPath != null) {
                RoutePlanFiles.delete(this, routePlanPath);
            }
            if (scriptJsonPath != null) {
                CacheFiles.deleteOwnedFile(this, scriptJsonPath);
            }
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }
}
