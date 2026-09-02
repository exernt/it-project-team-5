package com.example.walking_meditation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.walking_meditation.scheduler.*;
import com.example.walking_meditation.scheduler.factory.LocationQueueFactory;
import com.example.walking_meditation.scheduler.factory.SpeedQueueFactory;
import com.google.android.gms.location.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_LATITUDES;
import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_LONGITUDES;
import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_PATHS;

public class MeditationPlaybackActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final SchedulingMode MODE =
            SchedulingMode.LOCATION_ONLY;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private TextView tvStatus;
    private TextView tvCoordinates;

    private AudioScheduler scheduler;

    private List<LocationAudio> locationQueue;
    private SpeedAudioQueue speedQueue;

    // 🎵 Background white noise player
    private MediaPlayer whiteNoisePlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meditation_playback);

        tvStatus = findViewById(R.id.tvStatus);
        tvCoordinates = findViewById(R.id.tvCoordinates);

        fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(this);

        buildQueuesAndScheduler();
        startWhiteNoise();
    }


    private void startWhiteNoise() {

        whiteNoisePlayer = MediaPlayer.create(this, R.raw.white_noise);

        if (whiteNoisePlayer == null) return;

        whiteNoisePlayer.setLooping(true);

        // Lower volume (0.0f – 1.0f)
        whiteNoisePlayer.setVolume(0.3f, 0.3f);

        whiteNoisePlayer.start();
    }

    private void stopWhiteNoise() {
        if (whiteNoisePlayer != null) {
            whiteNoisePlayer.stop();
            whiteNoisePlayer.release();
            whiteNoisePlayer = null;
        }
    }

    private void buildQueuesAndScheduler() {
        ArrayList<String> audioPaths = getIntent().getStringArrayListExtra(EXTRA_AUDIO_PATHS);
        double[] latitudes = getIntent().getDoubleArrayExtra(EXTRA_AUDIO_LATITUDES);
        double[] longitudes = getIntent().getDoubleArrayExtra(EXTRA_AUDIO_LONGITUDES);

        if (audioPaths != null && latitudes != null && longitudes != null
                && audioPaths.size() == latitudes.length
                && audioPaths.size() == longitudes.length
                && !audioPaths.isEmpty()) {
            locationQueue = new ArrayList<>();
            for (int i = 0; i < audioPaths.size(); i++) {
                locationQueue.add(new LocationAudio(latitudes[i], longitudes[i], audioPaths.get(i)));
            }
        } else {
            locationQueue = LocationQueueFactory.createFromNavigationXml(this);
        }

        speedQueue =
                SpeedQueueFactory.createMeditationSpeedProfile();

        scheduler = new AudioSchedulerBuilder()
                .setContext(this)
                .setMode(MODE)
                .setLocationQueue(locationQueue)
                .setSpeedQueue(speedQueue)
                .build();

        tvStatus.setText("Mode: " + MODE.name());
    }


    private void requestLocationPermission() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {

        if (locationCallback != null) return;

        LocationRequest request =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        1000L
                ).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(
                    @NonNull LocationResult result) {

                for (Location location :
                        result.getLocations()) {

                    float speedKmph = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
                    scheduler.onLocationUpdate(location, speedKmph);
                    updateUI(location, speedKmph);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
            );
        }
    }


    private void updateUI(Location location, float speed) {

        double lat = location.getLatitude();
        double lon = location.getLongitude();

        StringBuilder builder = new StringBuilder();

        builder.append("Mode: ")
                .append(MODE.name())
                .append("\n\n");

        switch (MODE) {

            case LOCATION_ONLY:

                builder.append("Latitude: ").append(lat)
                        .append("\nLongitude: ").append(lon);

                if (scheduler instanceof LocationOnlyScheduler) {

                    float distance =
                            ((LocationOnlyScheduler) scheduler)
                                    .getDistanceToClosest(location);

                    builder.append("\nClosest Distance: ")
                            .append(String.format(Locale.getDefault(), "%.2f", distance))
                            .append(" meters");
                }
                break;

            case SPEED_ONLY:
                builder.append("Speed: ").append(speed)
                        .append(" km/h");
                break;

            case LOCATION_AND_SPEED:
                builder.append("Latitude: ").append(lat)
                        .append("\nLongitude: ").append(lon)
                        .append("\nSpeed: ").append(speed)
                        .append(" km/h");
                break;
        }

        tvCoordinates.setText(builder.toString());
    }


    @Override
    protected void onPause() {
        super.onPause();

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }

        if (whiteNoisePlayer != null && whiteNoisePlayer.isPlaying()) {
            whiteNoisePlayer.pause();
        }

        if (scheduler != null){
            scheduler.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        requestLocationPermission();

        if (whiteNoisePlayer != null && !whiteNoisePlayer.isPlaying()) {
            whiteNoisePlayer.start();
        }

        if (scheduler != null){
            scheduler.resume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }

        if (scheduler != null) scheduler.release();
        stopWhiteNoise();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == LOCATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0]
                == PackageManager.PERMISSION_GRANTED) {

            startLocationUpdates();
        } else {

            Toast.makeText(
                    this,
                    "Location permission denied",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
