package com.example.walking_meditation.scheduler;

import android.content.Context;
import android.location.Location;

import java.util.List;

public class LocationAndSpeedScheduler implements AudioScheduler {

    private final LocationOnlyScheduler locationScheduler;
    private final SpeedAudioQueue speedQueue;

    public LocationAndSpeedScheduler(Context context,
                                     List<LocationAudio> locationQueue,
                                     SpeedAudioQueue speedQueue) {

        this.locationScheduler =
                new LocationOnlyScheduler(context, locationQueue);

        this.speedQueue = speedQueue;
    }

    @Override
    public void onLocationUpdate(Location location, float speedKmph) {

        float target = speedQueue.getTargetSpeed();
        float tolerance = speedQueue.getTolerance();

        boolean speedMatches =
                Math.abs(speedKmph - target) <= tolerance;

        if (speedMatches) {
            locationScheduler.onLocationUpdate(location, speedKmph);
        }
    }

    @Override
    public void resume() {
        locationScheduler.resume();
    }

    @Override
    public void pause() {
        locationScheduler.pause();
    }

    @Override
    public void release() {
        locationScheduler.release();
    }
}
