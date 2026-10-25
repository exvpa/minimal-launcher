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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Landscape photos from Bing's daily image archive, cached on disk so the
 * screen still has a picture when offline. Each frame also carries a small,
 * pre-blurred copy that the glass panels draw behind themselves.
 */
final class Wallpapers {
    private static final String[] MARKETS = {"en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU"};
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

    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private String lastName;

    Wallpapers(Context context) {
        dir = new File(context.getFilesDir(), "wallpapers");
        dir.mkdirs();
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
    }

    /** Fetches new photos at most every 12 hours; runs onNew on the main thread if any arrived. */
    void refresh(final Runnable onNew) {
        io.execute(() -> {
            if (System.currentTimeMillis() - prefs.getLong("fetched", 0) < REFRESH_MS && files().length > 0) return;
            boolean added = false;
            for (String market : MARKETS) {
                for (int idx : new int[]{0, 7}) {
                    try {
                        String url = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx=" + idx + "&mkt=" + market;
                        JSONArray images = new JSONObject(Http.get(url)).getJSONArray("images");
                        for (int i = 0; i < images.length(); i++) {
                            String base = images.getJSONObject(i).getString("urlbase");
                            File file = new File(dir, name(base) + ".jpg");
                            if (file.exists()) continue;
                            Http.download("https://www.bing.com" + base + "_1920x1080.jpg", file);
                            added = true;
                        }
                    } catch (Exception ignored) {
                        // Try the next feed
                    }
                }
            }
            prefs.edit().putLong("fetched", System.currentTimeMillis()).apply();
            prune();
            if (added) main.post(onNew);
        });
    }

    /** Loads a random cached photo (never the same one twice in a row). */
    void next(final Callback callback) {
        io.execute(() -> {
            File[] files = files();
            if (files.length == 0) return;
            File pick = files[random.nextInt(files.length)];
            if (files.length > 1 && pick.getName().equals(lastName)) {
                pick = files[(Arrays.asList(files).indexOf(pick) + 1) % files.length];
            }
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

    private File[] files() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        return files == null ? new File[0] : files;
    }

    private void prune() {
        File[] files = files();
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
    static void boxBlur(Bitmap bmp, int radius, int passes) {
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
