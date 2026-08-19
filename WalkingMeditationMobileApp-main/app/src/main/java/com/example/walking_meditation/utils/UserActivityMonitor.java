package com.example.walking_meditation.utils;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.*;

public class UserActivityMonitor {

    public interface ActivityListener {
        void onActivityChanged(String activity, float speedMps, float speedKmph);
    }

    private static UserActivityMonitor instance;

    public static UserActivityMonitor getInstance(Context context) {
        if (instance == null) {
            instance = new UserActivityMonitor(context.getApplicationContext());
        }
        return instance;
    }

    private Context context;
    private ActivityRecognitionClient activityClient;
    private FusedLocationProviderClient locationClient;

    private PendingIntent pendingIntent;
    private LocationCallback locationCallback;

    private ActivityListener listener;

    private String currentActivity = "Unknown";
    private float currentSpeedMps = 0f;

    private UserActivityMonitor(Context context) {
        this.context = context;
        activityClient = ActivityRecognition.getClient(context);
        locationClient = LocationServices.getFusedLocationProviderClient(context);
    }

    // ===============================
    // Public API
    // ===============================

    public void setActivityListener(ActivityListener listener) {
        this.listener = listener;
    }

    public void start() {
        Intent intent = new Intent(context, ActivityRecognitionReceiver.class);
        intent.setAction("USER_ACTIVITY_ACTION");

        pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        activityClient.requestActivityUpdates(3000, pendingIntent);
    }

    public void stop() {
        if (pendingIntent != null) {
            if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            activityClient.removeActivityUpdates(pendingIntent);
        }
        stopLocationUpdates();
    }

    public String getCurrentActivity() {
        return currentActivity;
    }

    public float getSpeedMps() {
        return currentSpeedMps;
    }

    public float getSpeedKmph() {
        return currentSpeedMps * 3.6f;
    }

    // ===============================
    // Called from Receiver
    // ===============================

    void handleDetectedActivity(int type, int confidence) {

        if (confidence < 60) return;

        switch (type) {

            case DetectedActivity.WALKING:
                currentActivity = "Walking";
                startLocationUpdates();
                break;

            case DetectedActivity.STILL:
                currentActivity = "Sitting";
                currentSpeedMps = 0f;
                stopLocationUpdates();
                break;
        }

        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onActivityChanged(
                    currentActivity,
                    currentSpeedMps,
                    getSpeedKmph()
            );
        }
    }

    // ===============================
    // Location for Speed
    // ===============================

    private void startLocationUpdates() {

        LocationRequest request = LocationRequest.create();
        request.setInterval(3000);
        request.setPriority(Priority.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) {
                    currentSpeedMps = location.getSpeed();
                    notifyListener();
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            locationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
            );
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
        }
    }
}
