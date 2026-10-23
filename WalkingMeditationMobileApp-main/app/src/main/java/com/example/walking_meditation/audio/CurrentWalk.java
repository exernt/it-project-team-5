package com.example.walking_meditation.audio;

import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.ScheduledNarration;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/** The one locally retained walk. Only a complete manifest is playable. */
public final class CurrentWalk {
    private static final Gson GSON = new Gson();
    public final File directory;
    public static class Manifest {
        public String key;
        public RoutePlan route;
        public List<ScheduledNarration> narrations;
        public boolean ready;
        public int nextNarration;
        public long activeWalkingMs;
    }

    public CurrentWalk(File filesDir) { directory = new File(filesDir, "current_walk"); }

    public static String fingerprint(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    public Manifest begin(String key, RoutePlan route, List<ScheduledNarration> narrations)
            throws IOException {
        Manifest previous = null;
        try { previous = read(false); } catch (IOException ignored) { }
        if (previous != null && key.equals(previous.key)) return previous;
        clear();
        if (!directory.mkdirs()) throw new IOException("Cannot create current walk storage");
        Manifest manifest = new Manifest();
        manifest.key = key;
        manifest.route = route;
        manifest.narrations = narrations;
        save(manifest);
        return manifest;
    }

    public void save(Manifest manifest) throws IOException {
        File temporary = new File(directory, "manifest.part");
        Files.write(temporary.toPath(), GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(directory, "manifest.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public Manifest read(boolean requireReady) throws IOException {
        try {
            Manifest manifest = GSON.fromJson(new String(Files.readAllBytes(
                    new File(directory, "manifest.json").toPath()), StandardCharsets.UTF_8), Manifest.class);
            if (manifest == null || manifest.route == null || manifest.narrations == null
                    || manifest.narrations.isEmpty()) throw new IOException("Invalid walk manifest");
            if (requireReady) {
                if (!manifest.ready) throw new IOException("Walk audio is not ready");
                for (ScheduledNarration narration : manifest.narrations) {
                    if (narration.audioFilePath == null) throw new IOException("Missing narration audio");
                    File audio = new File(narration.audioFilePath);
                    if (!directory.getCanonicalFile().equals(audio.getCanonicalFile().getParentFile())
                            || !audio.isFile() || audio.length() == 0)
                        throw new IOException("Missing narration audio");
                }
            }
            return manifest;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid walk manifest", exception);
        }
    }

    public void clear() throws IOException { delete(directory); }
    private static void delete(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) throw new IOException("Cannot remove current walk data");
    }
}
