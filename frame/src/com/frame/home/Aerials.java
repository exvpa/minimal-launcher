package com.frame.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Apple TV aerial videos as a moving background, streamed from Apple's servers (nothing of Apple's is bundled).
 * The video list comes from Apple's entries.json; clips stream over plain HTTP, since
 * sylvan.apple.com's certificate chains to Apple's own root, which Android doesn't trust.
 */
final class Aerials implements TextureView.SurfaceTextureListener {
    private static final String LIST = "http://sylvan.apple.com/Aerials/resources-16.tar";
    private static final long LIST_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final int BLUR_W = 320, BLUR_H = 180;

    interface Listener {
        /** A new pre-blurred frame for the glass panels (main thread). */
        void onBlur(Bitmap blur);

        /** Video visible (true) or gone, e.g. stopped or failed (false). */
        void onVisible(boolean visible);

        /** The video's opacity changed while fading in or out. */
        void onFade();
    }

    final TextureView view;
    private final File cache;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<String> urls = new ArrayList<>();
    private MediaPlayer player;
    private Surface surface;
    private boolean running;
    private int lastIndex = -1;

    Aerials(Context context, Listener listener) {
        this.listener = listener;
        cache = new File(context.getFilesDir(), "aerials.json");
        view = new TextureView(context);
        view.setSurfaceTextureListener(this);
        view.setAlpha(0f);
    }

    void start() {
        if (running) return;
        running = true;
        if (urls.isEmpty()) loadList();
        else play();
        main.postDelayed(blurTick, 1500);
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        release();
        view.setAlpha(0f);
        listener.onVisible(false);
    }

    // --- video list

    private void loadList() {
        new Thread(() -> {
            List<String> found = new ArrayList<>();
            try {
                String json = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < LIST_MAX_AGE
                        ? read(cache) : fetchList();
                JSONArray assets = new JSONObject(json).getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    // 1080p H.264: decodes on any TV, and streams comfortably over Wi-Fi
                    String u = assets.getJSONObject(i).optString("url-1080-H264", "");
                    if (!u.isEmpty()) found.add(u.replace("https://", "http://"));
                }
            } catch (Exception ignored) {
                // Offline: the photo wallpaper stays
            }
            main.post(() -> {
                urls.clear();
                urls.addAll(found);
                if (running && !urls.isEmpty()) play();
            });
        }, "aerials").start();
    }

    /** entries.json is the first file of Apple's resources tar. */
    private String fetchList() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(LIST).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream()) {
            byte[] header = new byte[512];
            while (readFully(in, header)) {
                String name = new String(header, 0, 100, "UTF-8").trim().replace("\0", "");
                long size = Long.parseLong(new String(header, 124, 12, "US-ASCII").trim().replace("\0", ""), 8);
                if (name.isEmpty()) break;
                if (name.endsWith("entries.json")) {
                    byte[] data = new byte[(int) size];
                    if (!readFully(in, data)) break;
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(data);
                    }
                    return new String(data, "UTF-8");
                }
                long skip = (size + 511) / 512 * 512;
                while (skip > 0) skip -= in.skip(skip);
            }
        } finally {
            conn.disconnect();
        }
        throw new IOException("entries.json not found");
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // --- playback

    private void play() {
        if (!running || surface == null || urls.isEmpty()) return;
        release();
        int i = random.nextInt(urls.size());
        if (urls.size() > 1 && i == lastIndex) i = (i + 1) % urls.size();
        lastIndex = i;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setSurface(surface);
            mp.setDataSource(urls.get(i));
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                p.start();
                view.animate().alpha(1f).setDuration(1500).setUpdateListener(a -> listener.onFade()).start();
                listener.onVisible(true);
            });
            // Next clip: fade out, then load another
            mp.setOnCompletionListener(p -> view.animate().alpha(0f).setDuration(800)
                    .setUpdateListener(a -> listener.onFade()).withEndAction(this::play).start());
            mp.setOnErrorListener((p, what, extra) -> {
                main.postDelayed(this::play, 3000);
                return true;
            });
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            main.postDelayed(this::play, 3000);
        }
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** A small blurred copy of the current frame for the glass, twice a second. */
    private final Runnable blurTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (view.isAvailable() && view.getAlpha() > 0.5f) {
                Bitmap frame = view.getBitmap(BLUR_W, BLUR_H);
                if (frame != null) {
                    Wallpapers.boxBlur(frame, 8, 4);
                    listener.onBlur(frame);
                }
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (running) play();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
