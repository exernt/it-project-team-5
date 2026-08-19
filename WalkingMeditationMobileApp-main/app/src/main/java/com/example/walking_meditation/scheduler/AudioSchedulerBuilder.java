package com.example.walking_meditation.scheduler;

import android.content.Context;

import java.util.List;

public class AudioSchedulerBuilder {

    private SchedulingMode mode;
    private List<LocationAudio> locationQueue;
    private SpeedAudioQueue speedQueue;
    private Context context;

    public AudioSchedulerBuilder setMode(SchedulingMode mode) {
        this.mode = mode;
        return this;
    }

    public AudioSchedulerBuilder setLocationQueue(
            List<LocationAudio> locationQueue) {
        this.locationQueue = locationQueue;
        return this;
    }

    public AudioSchedulerBuilder setSpeedQueue(
            SpeedAudioQueue speedQueue) {
        this.speedQueue = speedQueue;
        return this;
    }

    public AudioSchedulerBuilder setContext(Context context) {
        this.context = context;
        return this;
    }

    public AudioScheduler build() {

        if (mode == null)
            throw new IllegalStateException("Mode not set");

        switch (mode) {

            case LOCATION_ONLY:
                return new LocationOnlyScheduler(
                        context,
                        locationQueue
                );

            case SPEED_ONLY:
                return new SpeedOnlyScheduler(
                        context,
                        speedQueue
                );

            case LOCATION_AND_SPEED:
                return new LocationAndSpeedScheduler(
                        context,
                        locationQueue,
                        speedQueue
                );

            default:
                throw new IllegalStateException("Invalid mode");
        }
    }
}