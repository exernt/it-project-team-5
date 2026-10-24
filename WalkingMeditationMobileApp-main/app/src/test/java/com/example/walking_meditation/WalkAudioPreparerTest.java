package com.example.walking_meditation;

import com.example.walking_meditation.audio.CurrentWalk;
import com.example.walking_meditation.audio.WalkAudioPreparer;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.ScheduledNarration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class WalkAudioPreparerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private List<ScheduledNarration> blocks() {
        ScheduledNarration a = new ScheduledNarration(); a.id = "a"; a.text = "One.";
        ScheduledNarration b = new ScheduledNarration(); b.id = "b"; b.text = "Two.";
        return Arrays.asList(a, b);
    }
    private class Source implements WalkAudioPreparer.AudioSource {
        final CurrentWalk store;
        int calls;
        boolean cancelled;
        Runnable afterDownload = () -> {};
        Source(CurrentWalk store) { this.store = store; }
        public String cacheKey(String text) { return text; }
        public File prepare(String text) throws Exception {
            calls++;
            File file = new File(store.directory, text + ".mp3");
            Files.write(file.toPath(), new byte[]{1});
            afterDownload.run();
            return file;
        }
        public void cancel() { cancelled = true; }
    }

    @Test public void onlyPublishesReadyAfterAllFilesAreSaved() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        Source source = new Source(store);
        source.afterDownload = () -> {
            try { assertFalse(store.read(false).ready); }
            catch (IOException e) { throw new RuntimeException(e); }
        };
        WalkAudioPreparer preparer = new WalkAudioPreparer(store, source);
        List<Integer> progress = new ArrayList<>();
        preparer.prepare(new RoutePlan(), "script", blocks(), (count, total) -> progress.add(count));
        assertTrue(store.read(true).ready);
        assertEquals(2, source.calls);
        assertEquals(Arrays.asList(0, 1), progress);
    }

    @Test public void cancellationDuringFinalDownloadCannotPublishReady() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        Source source = new Source(store);
        CountDownLatch lastDownload = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        source.afterDownload = () -> {
            if (source.calls == 2) {
                lastDownload.countDown();
                try { assertTrue(allowReturn.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        };
        WalkAudioPreparer preparer = new WalkAudioPreparer(store, source);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = worker.submit(() -> {
                try { preparer.prepare(new RoutePlan(), "script", blocks(), (a, b) -> {}); }
                catch (Exception e) { throw new CompletionException(e); }
            });
            assertTrue(lastDownload.await(5, TimeUnit.SECONDS));
            preparer.cancel();
            allowReturn.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getCause() instanceof InterruptedIOException);
            assertTrue(source.cancelled);
            assertFalse(store.read(false).ready);
            assertThrows(IOException.class, () -> store.read(true));
        } finally { allowReturn.countDown(); worker.shutdownNow(); }
    }

    @Test public void queuedCancellationAndDownloadFailureNeverEnablePlayback() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        Source source = new Source(store);
        WalkAudioPreparer preparer = new WalkAudioPreparer(store, source);
        preparer.cancel();
        assertThrows(InterruptedIOException.class, () ->
                preparer.prepare(new RoutePlan(), "script", blocks(), (a, b) -> {}));
        assertEquals(0, source.calls);
        source = new Source(store);
        source.afterDownload = () -> { throw new IllegalStateException("download failed"); };
        WalkAudioPreparer failed = new WalkAudioPreparer(store, source);
        assertThrows(IllegalStateException.class, () ->
                failed.prepare(new RoutePlan(), "script", blocks(), (a, b) -> {}));
        assertFalse(store.read(false).ready);
        assertThrows(IOException.class, () -> store.read(true));
    }
}
