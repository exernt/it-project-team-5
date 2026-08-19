package com.example.walking_meditation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.AudioAttributes;
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
        requestLocationPermission();
    }


    private void startWhiteNoise() {

        whiteNoisePlayer = MediaPlayer.create(this, R.raw.white_noise);

        if (whiteNoisePlayer == null) return;

        whiteNoisePlayer.setLooping(true);

        // Lower volume (0.0f – 1.0f)
        whiteNoisePlayer.setVolume(0.3f, 0.3f);

        // Set proper audio attributes
        whiteNoisePlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
        );

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

        locationQueue =
                LocationQueueFactory.createFromNavigationXml(this);

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

                    scheduler.onLocationUpdate(location, 0);
                    updateUI(location, 0);
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
                            .append(String.format("%.2f", distance))
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

        if (whiteNoisePlayer != null) {
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
        }

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