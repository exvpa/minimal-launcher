package com.frame.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo wallpapers from Bing, NASA, Unsplash or Pexels (or all of them mixed), cached
 * on disk so the screen still has a picture when offline. Each frame also carries a
 * small, pre-blurred copy that the glass panels draw behind themselves.
 *
 * Unsplash and Pexels need a free API key, and NASA works better with one. Keys are read
 * from keys.properties in the app's folder on the TV, never stored in the source:
 *   adb push keys.properties /sdcard/Android/data/com.frame.home/files/
 * with lines like  unsplash=...  pexels=...  nasa=...
 */
final class Wallpapers {
    static final String BING = "bing", NASA = "nasa", UNSPLASH = "unsplash", PEXELS = "pexels", MIXED = "mixed";
    static final String[] SOURCES = {BING, NASA, UNSPLASH, PEXELS};
    private static final String[] MARKETS = {"en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU"};
    /** Unsplash / Pexels searches; one is picked per refresh, so the mix keeps changing. */
    private static final String[] TOPICS = {"landscape", "nature", "mountains", "ocean", "forest", "city night",
            "architecture", "aerial", "desert", "lake", "northern lights", "minimal"};
    private static final long REFRESH_MS = 12 * 60 * 60 * 1000L;
    private static final int MAX_FILES = 60;
    private static final int BLUR_W = 320, BLUR_H = 180;

    static final class Frame {
        final Bitmap image;
        final Rect src;   // 16:9 centre crop of image
        final Bitmap blur;

        Frame(Bitmap image, Rect src, Bitmap blur) {
            this.image = image;
            this.src = src;
            this.blur = blur;
        }
    }

    interface Callback {
        void onFrame(Frame frame);
    }

    private final File root;
    private final File keysFile;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private volatile String source = BING;
    private String lastName;

    Wallpapers(Context context) {
        root = context.getFilesDir();
        File ext = context.getExternalFilesDir(null);
        keysFile = ext == null ? null : new File(ext, "keys.properties");
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
    }

    /** One of SOURCES, or MIXED. */
    void setSource(String s) {
        source = s;
    }

    /** Bing's folder keeps its original name, so photos cached before this change stay. */
    private File dir(String src) {
        File d = new File(root, src.equals(BING) ? "wallpapers" : "wallpapers-" + src);
        d.mkdirs();
        return d;
    }

    private String[] active() {
        return source.equals(MIXED) ? SOURCES : new String[]{source};
    }

    /** Whether a source can work: Unsplash and Pexels need a key. */
    boolean available(String src) {
        return src.equals(BING) || src.equals(NASA) || !key(src).isEmpty();
    }

    private String key(String src) {
        Properties p = new Properties();
        if (keysFile != null && keysFile.isFile()) {
            try (InputStream in = new FileInputStream(keysFile)) {
                p.load(in);
            } catch (IOException ignored) {
                // No keys: Unsplash and Pexels stay off
            }
        }
        String k = p.getProperty(src, "").trim();
        return k.isEmpty() && src.equals(NASA) ? "DEMO_KEY" : k;
    }

    /** Fetches new photos for the active source(s) at most every 12 hours; onNew runs if any arrived. */
    void refresh(final Runnable onNew) {
        io.execute(() -> {
            boolean added = false;
            for (String src : active()) {
                if (!available(src)) continue;
                String stamp = "fetched-" + src;
                if (System.currentTimeMillis() - prefs.getLong(stamp, 0) < REFRESH_MS && files(src).length > 0) continue;
                try {
                    switch (src) {
                        case NASA: added |= fetchNasa(); break;
                        case UNSPLASH: added |= fetchUnsplash(); break;
                        case PEXELS: added |= fetchPexels(); break;
                        default: added |= fetchBing(); break;
                    }
                } catch (Exception ignored) {
                    // Offline or over the API limit: try again next refresh
                }
                prefs.edit().putLong(stamp, System.currentTimeMillis()).apply();
                prune(src);
            }
            if (added) main.post(onNew);
        });
    }

    private boolean fetchBing() {
        boolean added = false;
        for (String market : MARKETS) {
            for (int idx : new int[]{0, 7}) {
                try {
                    String url = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx=" + idx + "&mkt=" + market;
                    JSONArray images = new JSONObject(Http.get(url)).getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        String base = images.getJSONObject(i).getString("urlbase");
                        added |= save(BING, name(base), "https://www.bing.com" + base + "_1920x1080.jpg");
                    }
                } catch (Exception ignored) {
                    // Try the next feed
                }
            }
        }
        return added;
    }

    /** NASA's Astronomy Picture of the Day, 25 random days; videos and small or tall images are skipped. */
    private boolean fetchNasa() throws Exception {
        JSONArray days = new JSONArray(Http.get("https://api.nasa.gov/planetary/apod?count=25&thumbs=false&api_key=" + key(NASA)));
        boolean added = false;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            if (!"image".equals(d.optString("media_type"))) continue;
            String url = d.optString("hdurl", d.optString("url", ""));
            if (url.isEmpty()) continue;
            added |= save(NASA, "apod" + d.optString("date", "" + i).replace("-", ""), url);
        }
        return added;
    }

    private boolean fetchUnsplash() throws Exception {
        String topic = TOPICS[random.nextInt(TOPICS.length)];
        String api = "https://api.unsplash.com/photos/random?count=20&orientation=landscape&content_filter=high&query="
                + URLEncoder.encode(topic, "UTF-8");
        JSONArray photos = new JSONArray(Http.get(api, "Authorization", "Client-ID " + key(UNSPLASH)));
        boolean added = false;
        for (int i = 0; i < photos.length(); i++) {
            JSONObject ph = photos.getJSONObject(i);
            String raw = ph.getJSONObject("urls").getString("raw");
            added |= save(UNSPLASH, ph.getString("id"), raw + "&w=2560&h=1440&fit=crop&q=85&fm=jpg");
        }
        return added;
    }

    private boolean fetchPexels() throws Exception {
        String topic = TOPICS[random.nextInt(TOPICS.length)];
        String api = "https://api.pexels.com/v1/search?orientation=landscape&size=large&per_page=30&page="
                + (1 + random.nextInt(5)) + "&query=" + URLEncoder.encode(topic, "UTF-8");
        JSONArray photos = new JSONObject(Http.get(api, "Authorization", key(PEXELS))).getJSONArray("photos");
        boolean added = false;
        for (int i = 0; i < photos.length(); i++) {
            JSONObject ph = photos.getJSONObject(i);
            String original = ph.getJSONObject("src").getString("original");
            added |= save(PEXELS, "px" + ph.getLong("id"), original + "?auto=compress&cs=tinysrgb&w=2560&h=1440&fit=crop");
        }
        return added;
    }

    /** Downloads one photo unless cached; drops it if it's too small or not landscape. */
    private boolean save(String src, String name, String url) {
        File file = new File(dir(src), name.replaceAll("[^A-Za-z0-9_-]", "") + ".jpg");
        if (file.exists()) return false;
        try {
            Http.download(url, file);
        } catch (Exception e) {
            return false;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth < 1600 || o.outWidth < o.outHeight * 1.3f) {
            file.delete();
            return false;
        }
        return true;
    }

    /** Loads a random cached photo from the active source(s), never the same one twice in a row. */
    void next(final Callback callback) {
        io.execute(() -> {
            List<File> all = new ArrayList<>();
            for (String src : active()) all.addAll(Arrays.asList(files(src)));
            // A source with nothing cached yet (e.g. just switched): show any cached photo meanwhile
            if (all.isEmpty()) for (String src : SOURCES) all.addAll(Arrays.asList(files(src)));
            if (all.isEmpty()) return;
            File pick = all.get(random.nextInt(all.size()));
            if (all.size() > 1 && pick.getName().equals(lastName)) pick = all.get((all.indexOf(pick) + 1) % all.size());
            lastName = pick.getName();
            final Frame frame = load(pick);
            if (frame != null) main.post(() -> callback.onFrame(frame));
        });
    }

    /** "/th?id=OHR.Lavender_EN-US1234" -> "Lavender", so the same photo in two markets is stored once. */
    private static String name(String urlbase) {
        String s = urlbase.substring(urlbase.indexOf('.') + 1);
        int cut = s.indexOf('_');
        return (cut > 0 ? s.substring(0, cut) : s).replaceAll("[^A-Za-z0-9]", "");
    }

    private File[] files(String src) {
        File[] files = dir(src).listFiles((d, n) -> n.endsWith(".jpg"));
        return files == null ? new File[0] : files;
    }

    private void prune(String src) {
        File[] files = files(src);
        if (files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) files[i].delete();
    }

    private static Frame load(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        if (opts.outWidth <= 0) {
            file.delete();
            return null;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = 1;
        while (opts.outWidth / (opts.inSampleSize * 2) >= 1920) opts.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), opts);
        if (image == null) return null;

        Rect src = crop16x9(image.getWidth(), image.getHeight());
        Bitmap blur = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
        new Canvas(blur).drawBitmap(image, src, new Rect(0, 0, BLUR_W, BLUR_H), new Paint(Paint.FILTER_BITMAP_FLAG));
        boxBlur(blur, 8, 4);
        return new Frame(image, src, blur);
    }

    private static Rect crop16x9(int w, int h) {
        if (w * 9 > h * 16) {
            int cw = h * 16 / 9;
            return new Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * 9 / 16;
        return new Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    /** Repeated separable box blur, which approximates a gaussian on the small bitmap. */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurLine(a, b, w, h, radius, true);
            blurLine(b, a, w, h, radius, false);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[index(line, clamp(i, len), w, horizontal)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
