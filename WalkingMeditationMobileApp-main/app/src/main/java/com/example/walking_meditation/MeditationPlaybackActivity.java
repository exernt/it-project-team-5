package com.example.walking_meditation;


import android.Manifest;
import com.example.walking_meditation.audio.CurrentWalk;
import androidx.appcompat.app.AlertDialog;
import android.media.MediaPlayer;
import java.io.File;
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

import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.RouteStep;
import com.example.walking_meditation.models.ScheduledNarration;
import com.example.walking_meditation.script.NarrationScheduler;
import com.example.walking_meditation.navigation.RouteProgressTracker;
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


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Full-screen selected-route navigation with prepared local narration. */
public class MeditationPlaybackActivity extends AppCompatActivity {
    private static final long LOCATION_INTERVAL_MS = 1_000L;
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

    private CurrentWalk store;
    private CurrentWalk.Manifest manifest;
    private boolean walkEnded;
    private boolean reachedDestination;
    private RoutePlan routePlan;
    private RouteProgressTracker progressTracker;
    private List<RouteStep> routeSteps = new ArrayList<>();
    private final List<ScheduledNarration> scheduledNarrations = new ArrayList<>();
    private final List<String> playbackLog = new ArrayList<>();

    @Nullable private Polyline completedLine;
    @Nullable private Polyline remainingLine;
    @Nullable private Polyline recoveryLine;
    @Nullable private Location previousLocation;

    @Nullable private MediaPlayer narrationPlayer;
    private boolean narrationPrepared;
    private boolean walkPaused;
    private boolean followUser = true;
    private boolean isNarrationPlaying = false;
    private int navigationStepIndex;
    private int narrationBlockIndex = 0;
    private int narrationAudioIndex = 0;
    private long activeWalkingMs;
    private long lastLocationRealtimeMs;

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
            loadPreparedWalk();
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

        findViewById(R.id.btn_end_walk).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("End this walk and delete its downloaded audio?")
                .setNegativeButton("Keep walking", null)
                .setPositiveButton("End walk", (dialog, which) -> endWalk()).show());
        pauseButton.setOnClickListener(view -> setWalkPaused(!walkPaused));
        recenterButton.setOnClickListener(view -> {
            followUser = true;
            recenterButton.setVisibility(View.GONE);
            if (previousLocation != null) followLocation(previousLocation);
        });

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this::onMapReady);
    }

    private void loadPreparedWalk() throws IOException {
        store = new CurrentWalk(getFilesDir());
        manifest = store.read(true);
        routePlan = manifest.route;
        scheduledNarrations.addAll(manifest.narrations);
        narrationBlockIndex = manifest.nextNarration;
        activeWalkingMs = manifest.activeWalkingMs;
        if (narrationBlockIndex < 0 || narrationBlockIndex > scheduledNarrations.size())
            throw new IOException("Invalid saved narration progress");
    }

    private void buildPlaybackSchedule() throws IOException {
        NarrationScheduler.resolve(routePlan, scheduledNarrations);
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
        reachedDestination = NarrationScheduler.hasArrived(
                match.progressM, progressTracker.getTotalDistanceM(), match.offRoute);
        updateRouteLines(match);
        updateNavigationInstruction(match, location);
        updatePlayback(match);
        if (reachedDestination && narrationBlockIndex >= scheduledNarrations.size()) {
            endWalk();
            return;
        }
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
        if (narrationBlockIndex >= scheduledNarrations.size()) return;

        ScheduledNarration next =
                scheduledNarrations.get(narrationBlockIndex);

        if (!NarrationScheduler.isEligible(next, match.progressM,
                activeWalkingMs, match.offRoute)) return;

        // Don't start another narration while one is playing.
        if (isNarrationPlaying) return;

        playScheduledNarration(next);
    }

    private void playScheduledNarration(ScheduledNarration narration) {
        narrationAudioIndex = 0;
        playNarrationAudio(narration);
    }

    private void playNarrationAudio(ScheduledNarration narration) {
        File audio = narration.audioFilePaths == null
                || narrationAudioIndex >= narration.audioFilePaths.size()
                ? null : new File(narration.audioFilePaths.get(narrationAudioIndex));
        if (audio == null || !audio.isFile() || audio.length() == 0) {
            setWalkPaused(true);
            routeStatusView.setText("Prepared narration audio is missing");
            return;
        }
        MediaPlayer player = new MediaPlayer();
        narrationPlayer = player;
        isNarrationPlaying = true;
        player.setOnPreparedListener(prepared -> {
            narrationPrepared = true;
            if (!walkPaused) prepared.start();
        });
        player.setOnCompletionListener(completed -> {
            releaseNarrationPlayer();
            narrationAudioIndex++;
            if (narrationAudioIndex < narration.audioFilePaths.size()) {
                playNarrationAudio(narration);
            } else {
                narrationAudioIndex = 0;
                onNarrationCompleted();
            }
        });
        player.setOnErrorListener((failed, what, extra) -> {
            releaseNarrationPlayer();
            setWalkPaused(true);
            routeStatusView.setText("Unable to play prepared narration");
            return true;
        });
        try {
            player.setDataSource(audio.getAbsolutePath());
            player.prepareAsync();
        } catch (Exception exception) {
            releaseNarrationPlayer();
            setWalkPaused(true);
            routeStatusView.setText("Unable to open prepared narration");
        }
    }

    private void releaseNarrationPlayer() {
        if (narrationPlayer != null) {
            narrationPlayer.release();
            narrationPlayer = null;
        }
        isNarrationPlaying = false;
        narrationPrepared = false;
    }

    private void onNarrationCompleted() {
        isNarrationPlaying = false;
        narrationBlockIndex++;
        saveProgress();
        if (reachedDestination && narrationBlockIndex >= scheduledNarrations.size()) endWalk();
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
        if (narrationPlayer != null && narrationPrepared) {
            if (paused && narrationPlayer.isPlaying()) narrationPlayer.pause();
            else if (!paused) {
                try { narrationPlayer.start(); } catch (IllegalStateException ignored) {
                    // Preparation callback starts audio once it is ready.
                }
            }
        }
        pauseButton.setText(paused ? "Resume" : "Pause");
        if (paused) {
            stopLocationUpdates();
            routeStatusView.setText("Walk paused");
        } else {
            routeStatusView.setText("Resuming guidance…");
            startLocationUpdates();
        }
    }

    private void saveProgress() {
        if (manifest == null || walkEnded) return;
        manifest.nextNarration = narrationBlockIndex;
        manifest.activeWalkingMs = activeWalkingMs;
        try { store.save(manifest); }
        catch (IOException e) { routeStatusView.setText("Unable to save walk progress"); }
    }

    private void endWalk() {
        setWalkPaused(true);
        try {
            store.finish(this::releaseNarrationPlayer);
            walkEnded = true;
            Toast.makeText(this, "Walk ended. Downloaded audio deleted.", Toast.LENGTH_LONG).show();
            finish();
        } catch (IOException e) {
            routeStatusView.setText("Unable to delete walk files. Tap End walk to retry.");
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
        setWalkPaused(true);
        saveProgress();
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
        releaseNarrationPlayer();
        mapView.onDestroy();
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
