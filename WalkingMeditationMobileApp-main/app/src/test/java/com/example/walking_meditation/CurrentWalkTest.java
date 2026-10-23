package com.example.walking_meditation;

import com.example.walking_meditation.audio.CurrentWalk;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.ScheduledNarration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.*;

public class CurrentWalkTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CurrentWalk.Manifest begin(CurrentWalk store, String key) throws Exception {
        ScheduledNarration n = new ScheduledNarration();
        n.id = "opening";
        n.text = "Welcome.";
        n.anchor = ScheduledNarration.Anchor.ROUTE_START;
        return store.begin(key, new RoutePlan(), Collections.singletonList(n));
    }

    @Test public void rejectsIncompleteAndMissingAudioAndPersistsProgress() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        CurrentWalk.Manifest m = begin(store, "first");
        assertThrows(IOException.class, () -> store.read(true));
        File audio = new File(store.directory, "opening.mp3");
        Files.write(audio.toPath(), new byte[]{1, 2, 3});
        m.narrations.get(0).audioFilePath = audio.getAbsolutePath();
        m.ready = true;
        m.nextNarration = 1;
        m.activeWalkingMs = 2000;
        store.save(m);
        CurrentWalk.Manifest loaded = store.read(true);
        assertEquals(1, loaded.nextNarration);
        assertEquals(2000, loaded.activeWalkingMs);
        assertEquals(ScheduledNarration.Anchor.ROUTE_START, loaded.narrations.get(0).anchor);
        Files.delete(audio.toPath());
        assertThrows(IOException.class, () -> store.read(true));
    }

    @Test public void retryRetainsDownloadsButNewWalkRemovesPreviousFiles() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        begin(store, "first");
        File audio = new File(store.directory, "finished.mp3");
        Files.write(audio.toPath(), new byte[]{1});
        begin(store, "first");
        assertTrue(audio.exists());
        begin(store, "second");
        assertFalse(audio.exists());
        assertEquals("second", store.read(false).key);
        store.clear();
        store.clear();
        assertFalse(store.directory.exists());
    }

    @Test public void refusesFilesOutsideCurrentWalkAndEmptyAudio() throws Exception {
        CurrentWalk store = new CurrentWalk(temp.getRoot());
        CurrentWalk.Manifest m = begin(store, "first");
        m.ready = true;
        File outside = temp.newFile("outside.mp3");
        Files.write(outside.toPath(), new byte[]{1});
        m.narrations.get(0).audioFilePath = outside.getAbsolutePath();
        store.save(m);
        assertThrows(IOException.class, () -> store.read(true));
        File empty = new File(store.directory, "empty.mp3");
        Files.write(empty.toPath(), new byte[0]);
        m.narrations.get(0).audioFilePath = empty.getAbsolutePath();
        store.save(m);
        assertThrows(IOException.class, () -> store.read(true));
    }
}
