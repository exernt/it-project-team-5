package com.example.walking_meditation.scheduler;

import android.location.Location;

public interface AudioScheduler {
    void onLocationUpdate(Location location, float speedKmph);

    void resume();

    void pause();
}