package com.example.walking_meditation.audio;

import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.ScheduledNarration;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.List;

/** Blocking preparation lifecycle; callers supply the worker and UI dispatcher. */
public final class WalkAudioPreparer {
    public interface AudioSource {
        File prepare(String text) throws Exception;
        String cacheKey(String text) throws Exception;
        void cancel();
    }
    public interface Progress { void update(int completed, int total); }
    private final CurrentWalk store;
    private final AudioSource source;
    private final Object completionLock = new Object();
    private volatile boolean cancelled;

    public WalkAudioPreparer(CurrentWalk store, AudioSource source) {
        this.store = store;
        this.source = source;
    }

    public CurrentWalk.Manifest prepare(RoutePlan route, String script,
            List<ScheduledNarration> narrations, Progress progress) throws Exception {
        checkCancelled();
        StringBuilder identity = new StringBuilder(route.routeId).append("\n").append(script);
        for (ScheduledNarration narration : narrations) {
            checkCancelled();
            identity.append("\n").append(source.cacheKey(narration.text));
        }
        checkCancelled();
        CurrentWalk.Manifest manifest = store.begin(CurrentWalk.fingerprint(identity.toString()), route, narrations);
        manifest.ready = false;
        store.save(manifest);
        for (int i = 0; i < manifest.narrations.size(); i++) {
            checkCancelled();
            progress.update(i, manifest.narrations.size());
            ScheduledNarration narration = manifest.narrations.get(i);
            File audio = source.prepare(narration.text);
            checkCancelled();
            narration.audioFilePath = audio.getAbsolutePath();
            store.save(manifest);
        }
        // Cancellation and publication have one ordering, even after the final download.
        synchronized (completionLock) {
            checkCancelled();
            store.markReady(manifest);
        }
        return manifest;
    }

    public void cancel() {
        synchronized (completionLock) { cancelled = true; }
        source.cancel();
    }

    public boolean isCancelled() { return cancelled; }
    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Audio preparation cancelled");
    }
}
