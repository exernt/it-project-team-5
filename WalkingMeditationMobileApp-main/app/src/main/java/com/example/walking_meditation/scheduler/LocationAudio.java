package com.example.walking_meditation.scheduler;

public class LocationAudio {

    public final double latitude;
    public final double longitude;
    public final int audioResId;

    public boolean isInside = false;
    public long lastPlayedAtMs = 0L;

    public LocationAudio(double latitude,
                         double longitude,
                         int audioResId) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.audioResId = audioResId;
    }
}