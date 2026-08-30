package com.example.walking_meditation.scheduler;

import android.content.Context;
import android.location.Location;
import android.media.MediaPlayer;
import android.widget.Toast;

import java.util.List;

public class LocationOnlyScheduler implements AudioScheduler {

    private static final float RADIUS_METERS = 20f;
    private static final long COOLDOWN_MS = 15_000L;

    private final Context context;
    private final List<LocationAudio> queue;

    private MediaPlayer mediaPlayer;
    private int nextTargetIndex = 0;
    private boolean resumePlayback = false;

    public LocationOnlyScheduler(Context context,
                                 List<LocationAudio> queue) {
        this.context = context;
        this.queue = queue;

    }

    @Override
    public void onLocationUpdate(Location location, float speedKmph) {
        if (nextTargetIndex >= queue.size()) return;
        if (mediaPlayer != null && mediaPlayer.isPlaying()) return;

        LocationAudio target = queue.get(nextTargetIndex);

            float[] results = new float[1];

            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(), target.latitude, target.longitude, results);

            float distance = results[0];
            float effectiveRadius = location.hasAccuracy()
                    ? Math.max(RADIUS_METERS, location.getAccuracy())
                    : RADIUS_METERS;
            boolean nowInside = distance <= effectiveRadius;

            if (!target.isInside && nowInside) {

                target.isInside = true;

                long now = System.currentTimeMillis();

                if (now - target.lastPlayedAtMs >= COOLDOWN_MS) {
                    target.lastPlayedAtMs = now;
                    if (play(target)) nextTargetIndex++;
                }
            }

            if (target.isInside && !nowInside) {
                target.isInside = false;
            }
    }

    public float getDistanceToClosest(Location location) {

        if (queue == null || queue.isEmpty()) {
            return -1f;
        }

        if (nextTargetIndex >= queue.size()) return 0f;
        LocationAudio target = queue.get(nextTargetIndex);

            float[] results = new float[1];

            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(),
                    target.latitude,
                    target.longitude,
                    results
            );

        return results[0];
    }

    private boolean play(LocationAudio target) {

        if (mediaPlayer != null) {
            mediaPlayer.release();
        }

        try {
            if (target.audioFilePath != null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(target.audioFilePath);
                mediaPlayer.prepare();
            } else {
                mediaPlayer = MediaPlayer.create(context, target.audioResId);
            }
            if (mediaPlayer != null) {
                mediaPlayer.start();
                return true;
            }
        } catch (Exception e) {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = null;
            Toast.makeText(context, "Unable to play narration", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    @Override
    public void resume() {
        if (resumePlayback && mediaPlayer != null && !mediaPlayer.isPlaying()){
            mediaPlayer.start();
        }
        resumePlayback = false;
    }

    @Override
    public void pause() {
        if(mediaPlayer != null && mediaPlayer.isPlaying()){
            resumePlayback = true;
            mediaPlayer.pause();
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        resumePlayback = false;
    }
}
