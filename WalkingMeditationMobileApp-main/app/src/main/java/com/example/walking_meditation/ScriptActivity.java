package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.walking_meditation.adapters.NarrationAdapter;
import com.example.walking_meditation.models.NarrationItem;
import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.utils.PolylineDecoder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_LATITUDES;
import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_LONGITUDES;
import static com.example.walking_meditation.nav.Extras.EXTRA_AUDIO_PATHS;
import static com.example.walking_meditation.nav.Extras.EXTRA_ROUTE_POLYLINES;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;


// TO BE DEPRECATED

public class ScriptActivity extends AppCompatActivity implements NarrationAdapter.OnNarrationClickListener {

    // NEW: matches RouteOptionsActivity intent extra key
    public static final String EXTRA_SCRIPT_JSON_PATH = "script_json_path";

    private static final String BASE_URL = BuildConfig.SERVER_BASE_URL;
    private static final String TTS_URL = BASE_URL + "/tts";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private RecyclerView recyclerView;
    private NarrationAdapter adapter;
    private FloatingActionButton fabDownloadAll;

    private final List<NarrationItem> narrationItems = new ArrayList<>();

    // Queue state
    private boolean isQueueRunning = false;
    private boolean isDownloadActive = false;
    private int currentQueueIndex = -1;
    private boolean destroyed = false;
    private Call activeTtsCall;
    private ArrayList<String> routePolylines = new ArrayList<>();
    private String scriptId = "script";

    // OkHttp with reasonable timeouts (tts mp3 can take time)
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script);

        recyclerView = findViewById(R.id.recyclerView);
        fabDownloadAll = findViewById(R.id.fabDownloadAll);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        ArrayList<String> receivedPolylines = getIntent().getStringArrayListExtra(EXTRA_ROUTE_POLYLINES);
        if (receivedPolylines != null) routePolylines = receivedPolylines;

        // NEW: read JSON from file path extra
        String path = getIntent().getStringExtra(EXTRA_SCRIPT_JSON_PATH);
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "Missing script file path", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String scriptResponseJson;
        try {
            scriptResponseJson = readTextFile(path);
        } catch (IOException ioe) {
            Toast.makeText(this, "Failed to read script file: " + ioe.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (scriptResponseJson.trim().isEmpty()) {
            Toast.makeText(this, "Script file is empty", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Extract XML from JSON field "script"
        final String xml;
        try {
            JSONObject obj = new JSONObject(scriptResponseJson);
            xml = obj.optString("script", "").trim();
        } catch (Exception e) {
            Toast.makeText(this, "Invalid script JSON", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (xml.isEmpty()) {
            Toast.makeText(this, "No script field in response JSON", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        scriptId = Integer.toHexString(xml.hashCode());

        narrationItems.clear();
        narrationItems.addAll(parseNarrations(xml));

        if (narrationItems.isEmpty()) {
            Toast.makeText(this, "No narration blocks found in XML", Toast.LENGTH_LONG).show();
        }

        adapter = new NarrationAdapter(narrationItems, this);
        recyclerView.setAdapter(adapter);
        restoreExistingDownloads();
        updateFabState();
        fabDownloadAll.setOnClickListener(v -> {
            if (allNarrationsDownloaded()) {
                startPlayback();
            } else {
                startDownloadQueue();
            }
        });
    }

    /**
     * Tap behavior:
     * - ERROR -> retry only that block
     * - DOWNLOADED -> toast (optional)
     * - DOWNLOADING -> ignore
     * - READY -> ignore (we want single FAB to reduce burden)
     */
    @Override
    public void onNarrationClicked(NarrationItem item, int position) {
        if (isDownloadActive) return;
        if (item.status == NarrationItem.Status.DOWNLOADING) return;

        if (item.status == NarrationItem.Status.DOWNLOADED && item.localFilePath != null) {
            Toast.makeText(this, "Already downloaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (item.status == NarrationItem.Status.ERROR || item.status == NarrationItem.Status.READY) {
            downloadSingleItem(position);
        }
    }

    private void startDownloadQueue() {
        if (isQueueRunning || isDownloadActive) {
            Toast.makeText(this, "Download already running…", Toast.LENGTH_SHORT).show();
            return;
        }

        int start = findNextDownloadIndex(-1);
        if (start == -1) {
            Toast.makeText(this, "All narration MP3s are downloaded", Toast.LENGTH_SHORT).show();
            return;
        }

        isQueueRunning = true;
        fabDownloadAll.setEnabled(false);
        fabDownloadAll.setAlpha(0.5f);

        currentQueueIndex = start;
        downloadItemAtIndex(currentQueueIndex, true);
    }

    private void stopDownloadQueue() {
        isQueueRunning = false;
        currentQueueIndex = -1;
        updateFabState();
    }

    /**
     * Find next item that needs download AFTER currentIndex
     * Eligible statuses: READY or ERROR
     */
    private int findNextDownloadIndex(int currentIndex) {
        for (int i = currentIndex + 1; i < narrationItems.size(); i++) {
            NarrationItem item = narrationItems.get(i);
            if (item.status == NarrationItem.Status.READY || item.status == NarrationItem.Status.ERROR) {
                return i;
            }
        }
        return -1;
    }

    private void downloadSingleItem(int position) {
        if (isQueueRunning) {
            Toast.makeText(this, "Queue running—please wait or retry after it finishes.", Toast.LENGTH_SHORT).show();
            return;
        }
        downloadItemAtIndex(position, false);
    }

    private void downloadItemAtIndex(int position, boolean fromQueue) {
        if (position < 0 || position >= narrationItems.size()) {
            if (fromQueue) stopDownloadQueue();
            return;
        }

        NarrationItem item = narrationItems.get(position);

        if (item.status == NarrationItem.Status.DOWNLOADING) return;
        if (item.status == NarrationItem.Status.DOWNLOADED) {
            if (fromQueue) advanceQueue(position);
            return;
        }

        downloadTTS(item, position, fromQueue);
    }

    private void advanceQueue(int justFinishedIndex) {
        int next = findNextDownloadIndex(justFinishedIndex);
        if (next == -1) {
            stopDownloadQueue();
            Toast.makeText(
                    this,
                    allNarrationsDownloaded()
                            ? "All downloads completed"
                            : "Some downloads failed. Tap download to retry.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        currentQueueIndex = next;
        downloadItemAtIndex(currentQueueIndex, true);
    }

    private List<NarrationItem> parseNarrations(String xml) {
        List<NarrationItem> items = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Document doc = builder.parse(is);

            NodeList narrationNodes = doc.getElementsByTagName("narration");
            for (int i = 0; i < narrationNodes.getLength(); i++) {
                Element el = (Element) narrationNodes.item(i);

                String id = el.getAttribute("id");
                if (id == null || id.trim().isEmpty()) {
                    id = String.valueOf(i + 1);
                }

                String text = el.getTextContent();
                if (text != null) text = text.trim();

                if (text != null && !text.isEmpty()) {
                    addNarrationChunks(items, id, text);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return items;
    }

    private void addNarrationChunks(List<NarrationItem> items, String id, String text) {
        final int maxChars = 4000;
        int chunk = 1;
        String remaining = text.trim();
        while (remaining.length() > maxChars) {
            int split = remaining.lastIndexOf(' ', maxChars);
            if (split < maxChars / 2) split = maxChars;
            items.add(new NarrationItem(id + "-" + chunk, remaining.substring(0, split).trim()));
            remaining = remaining.substring(split).trim();
            chunk++;
        }
        if (!remaining.isEmpty()) {
            items.add(new NarrationItem(chunk == 1 ? id : id + "-" + chunk, remaining));
        }
    }

    // this is the main tts functionality

    private void downloadTTS(NarrationItem item, int position, boolean fromQueue) {
        isDownloadActive = true;
        item.status = NarrationItem.Status.DOWNLOADING;
        item.progress = 0;
        adapter.notifyItemChanged(position);
        updateFabState();

        try {
            JSONObject payload = new com.example.walking_meditation.audio.PollyPayload().toJson(item.text);

            RequestBody body = RequestBody.create(payload.toString(), JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(TTS_URL)
                    .post(body);
            if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty()) {
                requestBuilder.header("X-App-Token", BuildConfig.SERVER_ACCESS_TOKEN);
            }
            Request request = requestBuilder.build();

            activeTtsCall = httpClient.newCall(request);
            activeTtsCall.enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        item.status = NarrationItem.Status.ERROR;
                        isDownloadActive = false;
                        item.progress = 0;
                        adapter.notifyItemChanged(position);

                        Toast.makeText(ScriptActivity.this,
                                "TTS failed (item " + (position + 1) + "): " + e.getMessage(),
                                Toast.LENGTH_LONG).show();

                        if (fromQueue) {
                            advanceQueue(position);
                        } else {
                            updateFabState();
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    ResponseBody responseBody = response.body();
                    if (!response.isSuccessful() || responseBody == null) {
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            item.status = NarrationItem.Status.ERROR;
                            isDownloadActive = false;
                            item.progress = 0;
                            adapter.notifyItemChanged(position);

                            Toast.makeText(ScriptActivity.this,
                                    "Server error (item " + (position + 1) + "): " + response.code(),
                                    Toast.LENGTH_LONG).show();

                            if (fromQueue) {
                                advanceQueue(position);
                            } else {
                                updateFabState();
                            }
                        });
                        response.close();
                        return;
                    }

                    File outFile = new File(getFilesDir(), buildMp3FileName(item, position));

                    try (InputStream in = responseBody.byteStream();
                         FileOutputStream out = new FileOutputStream(outFile)) {

                        long contentLength = responseBody.contentLength(); // may be -1
                        byte[] buffer = new byte[8192];
                        long totalRead = 0;
                        int read;

                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            totalRead += read;

                            if (contentLength > 0) {
                                int p = (int) ((totalRead * 100L) / contentLength);
                                if (p > 100) p = 100;
                                item.progress = p;

                                runOnUiThread(() -> {
                                    if (!destroyed) adapter.notifyItemChanged(position);
                                });
                            }
                        }

                        out.flush();

                        runOnUiThread(() -> {
                            if (destroyed) return;
                            item.status = NarrationItem.Status.DOWNLOADED;
                            isDownloadActive = false;
                            item.progress = 100;
                            item.localFilePath = outFile.getAbsolutePath();
                            adapter.notifyItemChanged(position);

                            if (fromQueue) {
                                advanceQueue(position);
                            } else {
                                updateFabState();
                                Toast.makeText(ScriptActivity.this,
                                        "Downloaded MP3: " + outFile.getName(),
                                        Toast.LENGTH_SHORT).show();
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            item.status = NarrationItem.Status.ERROR;
                            isDownloadActive = false;
                            item.progress = 0;
                            adapter.notifyItemChanged(position);

                            Toast.makeText(ScriptActivity.this,
                                    "Save failed (item " + (position + 1) + "): " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();

                            if (fromQueue) {
                                advanceQueue(position);
                            } else {
                                updateFabState();
                            }
                        });
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            item.status = NarrationItem.Status.ERROR;
            isDownloadActive = false;
            item.progress = 0;
            adapter.notifyItemChanged(position);

            Toast.makeText(this, "JSON error: " + e.getMessage(), Toast.LENGTH_LONG).show();

            if (fromQueue) {
                advanceQueue(position);
            } else {
                updateFabState();
            }
        }
    }

    private String buildMp3FileName(NarrationItem item, int position) {
        return "tts_polly_v1_" + scriptId + "_" + sanitize(item.id) + "_" + position + ".mp3";
    }

    private void restoreExistingDownloads() {
        for (int i = 0; i < narrationItems.size(); i++) {
            NarrationItem item = narrationItems.get(i);
            File file = new File(getFilesDir(), buildMp3FileName(item, i));
            if (file.isFile() && file.length() > 0) {
                item.status = NarrationItem.Status.DOWNLOADED;
                item.progress = 100;
                item.localFilePath = file.getAbsolutePath();
            }
        }
        adapter.notifyDataSetChanged();
    }

    private boolean allNarrationsDownloaded() {
        if (narrationItems.isEmpty()) return false;
        for (NarrationItem item : narrationItems) {
            if (item.status != NarrationItem.Status.DOWNLOADED || item.localFilePath == null) {
                return false;
            }
        }
        return true;
    }

    private void updateFabState() {
        fabDownloadAll.setEnabled(!isQueueRunning && !isDownloadActive && !narrationItems.isEmpty());
        fabDownloadAll.setAlpha(fabDownloadAll.isEnabled() ? 1f : 0.5f);
        fabDownloadAll.setImageResource(
                allNarrationsDownloaded()
                        ? android.R.drawable.ic_media_play
                        : android.R.drawable.stat_sys_download
        );
    }

    private void startPlayback() {
        List<LatLon> points = new ArrayList<>();
        try {
            for (String encoded : routePolylines) {
                List<LatLon> decoded = PolylineDecoder.decode(encoded);
                if (!points.isEmpty() && !decoded.isEmpty()) decoded.remove(0);
                points.addAll(decoded);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "The selected route geometry is invalid.", Toast.LENGTH_LONG).show();
            return;
        }
        if (points.isEmpty()) {
            Toast.makeText(this, "The selected route has no playback coordinates.", Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<String> paths = new ArrayList<>();
        double[] latitudes = new double[narrationItems.size()];
        double[] longitudes = new double[narrationItems.size()];
        for (int i = 0; i < narrationItems.size(); i++) {
            NarrationItem item = narrationItems.get(i);
            paths.add(item.localFilePath);
            int pointIndex = narrationItems.size() == 1
                    ? 0
                    : (int) Math.round(i * (points.size() - 1.0) / (narrationItems.size() - 1.0));
            LatLon point = points.get(pointIndex);
            latitudes[i] = point.lat;
            longitudes[i] = point.lon;
        }

        Intent intent = new Intent(this, MeditationPlaybackActivity.class);
        intent.putStringArrayListExtra(EXTRA_AUDIO_PATHS, paths);
        intent.putExtra(EXTRA_AUDIO_LATITUDES, latitudes);
        intent.putExtra(EXTRA_AUDIO_LONGITUDES, longitudes);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (activeTtsCall != null) {
            activeTtsCall.cancel();
            activeTtsCall = null;
        }
        super.onDestroy();
    }

    private String sanitize(String s) {
        if (s == null) return "n";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /**
     * Reads a UTF-8 text file fully and returns its contents as a String.
     */
    @NonNull
    private String readTextFile(@NonNull String absolutePath) throws IOException {
        File f = new File(absolutePath);
        if (!f.exists()) throw new IOException("File not found");
        if (!f.isFile()) throw new IOException("Not a file");

        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1024 * 1024));
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
