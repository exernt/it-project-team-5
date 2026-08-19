package com.example.walking_meditation.scheduler;

import android.content.Context;
import android.location.Location;
import android.media.MediaPlayer;
import android.widget.Toast;

import java.util.List;

public class LocationOnlyScheduler implements AudioScheduler {

    private static final float RADIUS_METERS = 5f;
    private static final long COOLDOWN_MS = 15_000L;

    private final Context context;
    private final List<LocationAudio> queue;

    private MediaPlayer mediaPlayer;

    public LocationOnlyScheduler(Context context,
                                 List<LocationAudio> queue) {
        this.context = context;
        this.queue = queue;

        Toast.makeText(
                this.context,
                "Queue size: " + this.queue.size(),
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    public void onLocationUpdate(Location location, float speedKmph) {

        for (LocationAudio target : queue) {

            float[] results = new float[1];

            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(), target.latitude, target.longitude, results);

            float distance = results[0];
            boolean nowInside = distance <= RADIUS_METERS;

            if (!target.isInside && nowInside) {

                target.isInside = true;

                long now = System.currentTimeMillis();

                if (now - target.lastPlayedAtMs >= COOLDOWN_MS) {
                    target.lastPlayedAtMs = now;
                    play(target.audioResId);
                    break;
                }
            }

            if (target.isInside && !nowInside) {
                target.isInside = false;
            }
        }
    }

    public float getDistanceToClosest(Location location) {

        if (queue == null || queue.isEmpty()) {
            return -1f;
        }

        float minDistance = Float.MAX_VALUE;

        for (LocationAudio target : queue) {

            float[] results = new float[1];

            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(),
                    target.latitude,
                    target.longitude,
                    results
            );

            float distance = results[0];

            if (distance < minDistance) {
                minDistance = distance;
            }
        }

        return minDistance;
    }

    private void play(int resId) {

        if (mediaPlayer != null) {
            mediaPlayer.release();
        }

        mediaPlayer = MediaPlayer.create(context, resId);

        if (mediaPlayer != null) {
            mediaPlayer.start();
        }
    }

    @Override
    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()){
            mediaPlayer.start();
        }
    }

    @Override
    public void pause() {
        if(mediaPlayer != null && mediaPlayer.isPlaying()){
            mediaPlayer.pause();
        }
    }
}