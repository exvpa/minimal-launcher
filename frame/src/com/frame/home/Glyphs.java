package com.frame.home;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Monochrome glyphs drawn from simple shapes. Coordinates are in units where
 * the glyph's nominal height runs from -1 to 1; u converts units to pixels.
 */
final class Glyphs {
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Typeface BLACK = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private static final Typeface SERIF = Typeface.create("serif", Typeface.BOLD_ITALIC);

    private static float u, cx, cy;

    /**
     * Icon library: "<id>.png", where id is an app's package name (or "apps"). Looked up first in the
     * TV folder (Android/data/com.frame.home/files/icons, filled with adb push), then in the bundled
     * assets/icons/. Loaded on first use and cropped to their visible pixels.
     * In color mode, icons-color/ (TV folder, then assets) comes first.
     */
    private static final Map<String, Bitmap> images = new HashMap<>();
    private static final Set<String> missing = new HashSet<>();
    private static final Set<String> colored = new HashSet<>();   // ids whose loaded image is the color version
    private static boolean colorMode;
    private static final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static AssetManager assets;
    private static File folder;

    private Glyphs() {}

    static void init(Context context) {
        assets = context.getAssets();
        folder = context.getExternalFilesDir(null);
    }

    /** White (minimal) or color logos; switching reloads the icons. */
    static void setColorMode(boolean color) {
        if (color == colorMode) return;
        colorMode = color;
        reload();
    }

    /** Forgets loaded icons, so files newly added to the TV folder show up. */
    static void reload() {
        images.clear();
        missing.clear();
        colored.clear();
    }

    static boolean hasImage(String id) {
        return image(id) != null;
    }

    private static Bitmap image(String id) {
        Bitmap bmp = images.get(id);
        if (bmp != null || missing.contains(id)) return bmp;
        bmp = decode(id);
        if (bmp == null) missing.add(id);
        else images.put(id, bmp);
        return bmp;
    }

    private static Bitmap decode(String id) {
        Bitmap raw = null;
        if (colorMode) {
            raw = load("icons-color", id);
            if (raw != null) colored.add(id);
        }
        // White set, also the fallback for logos missing from the color set
        if (raw == null) raw = load("icons", id);
        if (raw == null) return null;
        Rect b = visibleBounds(raw);
        // The dock draws logos at about 60px high
        float scale = Math.min(1f, 240f / b.height());
        Bitmap cropped = Bitmap.createBitmap(raw, b.left, b.top, b.width(), b.height());
        return Bitmap.createScaledBitmap(cropped,
                Math.max(1, Math.round(b.width() * scale)), Math.max(1, Math.round(b.height() * scale)), true);
    }

    /** TV folder first (adb push), then the bundled assets. */
    private static Bitmap load(String set, String id) {
        File file = folder == null ? null : new File(new File(folder, set), id + ".png");
        if (file != null && file.isFile()) {
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath());
            if (bmp != null) return bmp;
        }
        if (assets == null) return null;
        try (InputStream in = assets.open(set + "/" + id + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static Rect visibleBounds(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 8) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        return r < 0 ? new Rect(0, 0, w, h) : new Rect(l, t, r + 1, b + 1);
    }

    /** Draws a logo image: white ones recoloured to the glyph colour, color ones only faded by its alpha. */
    private static boolean drawImage(Canvas c, String id, float centerX, float centerY, float size, int color) {
        Bitmap bmp = image(id);
        if (bmp == null) return false;
        float h = size * 1.15f, w = h * bmp.getWidth() / bmp.getHeight();
        if (w > size * 2.6f) {
            // Very wide wordmarks: fit the width instead
            h *= size * 2.6f / w;
            w = size * 2.6f;
        }
        float r = Color.red(color) / 255f, g = Color.green(color) / 255f, b = Color.blue(color) / 255f, a = Color.alpha(color) / 255f;
        // Slight contrast on alpha: fades the faint smudges left from background removal
        imagePaint.setColorFilter(new ColorMatrixColorFilter(colored.contains(id) ? new float[]{
                1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 1.2f * a, -25f * a,
        } : new float[]{
                0, 0, 0, 0, r * 255,
                0, 0, 0, 0, g * 255,
                0, 0, 0, 0, b * 255,
                0, 0, 0, 1.2f * a, -25f * a,
        }));
        c.drawBitmap(bmp, null, new RectF(centerX - w / 2, centerY - h / 2, centerX + w / 2, centerY + h / 2), imagePaint);
        return true;
    }

    static void draw(Canvas c, String id, float centerX, float centerY, float size, int color) {
        if (drawImage(c, id, centerX, centerY, size, color)) return;
        u = size / 2f;
        cx = centerX;
        cy = centerY;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (id) {
            case "youtube": youtube(c); break;
            case "netflix": netflix(c); break;
            case "appletv": appleTv(c); break;
            case "disney": disney(c); break;
            case "plex": plex(c); break;
            case "max": word(c, "max", BLACK, 1.75f, 0f); break;
            case "sky": word(c, "sky", BLACK, 1.85f, 0f); break;
            case "tv": tv(c); break;
            case "apps": apps(c); break;
            case "search": search(c); break;
            case "store": store(c); break;
            case "settings": settings(c); break;
            case "clear-day": sun(c, 0, 0, 1f); break;
            case "clear-night": moon(c); break;
            case "partly-day": sun(c, -0.38f, -0.32f, 0.7f); c.drawPath(cloud(0.12f, 0.22f, 0.8f), paint); break;
            case "partly-night": c.drawPath(cloud(0f, 0.05f, 1f), paint); break;
            case "cloudy": c.drawPath(cloud(0f, 0.05f, 1f), paint); break;
            case "fog": fog(c); break;
            case "rain": precipitation(c, false); break;
            case "snow": precipitation(c, true); break;
            case "storm": storm(c); break;
            default: break;
        }
    }

    // --- helpers: unit coordinates -> pixels

    private static float x(float v) { return cx + v * u; }
    private static float y(float v) { return cy + v * u; }

    private static RectF rect(float l, float t, float r, float b) {
        return new RectF(x(l), y(t), x(r), y(b));
    }

    private static Path circle(float ux, float uy, float ur) {
        Path p = new Path();
        p.addCircle(x(ux), y(uy), ur * u, Path.Direction.CW);
        return p;
    }

    private static Path poly(float... pts) {
        Path p = new Path();
        p.moveTo(x(pts[0]), y(pts[1]));
        for (int i = 2; i < pts.length; i += 2) p.lineTo(x(pts[i]), y(pts[i + 1]));
        p.close();
        return p;
    }

    private static void stroke(float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * u);
    }

    private static void word(Canvas c, String text, Typeface tf, float size, float dx) {
        paint.setTypeface(tf);
        paint.setTextSize(size * u);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        // Centre on the x-height so lowercase words sit in the middle of the glyph box
        float xHeight = -fm.ascent * 0.52f;
        c.drawText(text, x(dx), cy + xHeight / 2f, paint);
        paint.setTypeface(null);
    }

    // --- app logos

    private static void youtube(Canvas c) {
        Path p = new Path();
        float r = 0.42f * u;
        p.addRoundRect(rect(-1.42f, -1f, 1.42f, 1f), r, r, Path.Direction.CW);
        p.addPath(poly(-0.4f, -0.5f, 0.58f, 0f, -0.4f, 0.5f));
        p.setFillType(Path.FillType.EVEN_ODD);
        c.drawPath(p, paint);
    }

    private static void netflix(Canvas c) {
        c.drawRect(rect(-0.62f, -1f, -0.22f, 1f), paint);
        c.drawRect(rect(0.22f, -1f, 0.62f, 1f), paint);
        c.drawPath(poly(-0.62f, -1f, -0.22f, -1f, 0.62f, 1f, 0.22f, 1f), paint);
    }

    private static void appleTv(Canvas c) {
        // Apple: two overlapping circles, a bite, and a leaf
        float ax = -0.95f;
        Path apple = circle(ax - 0.24f, 0.18f, 0.5f);
        apple.op(circle(ax + 0.24f, 0.18f, 0.5f), Path.Op.UNION);
        apple.op(circle(ax + 0.74f, 0.02f, 0.25f), Path.Op.DIFFERENCE);
        c.drawPath(apple, paint);
        Path leaf = new Path();
        leaf.addOval(rect(ax - 0.06f, -0.88f, ax + 0.2f, -0.38f), Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(35, x(ax + 0.07f), y(-0.63f));
        leaf.transform(m);
        c.drawPath(leaf, paint);
        // "tv"
        paint.setTypeface(MEDIUM);
        paint.setTextSize(1.9f * u);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("tv", x(-0.25f), y(0.68f), paint);
        paint.setTypeface(null);
    }

    private static void disney(Canvas c) {
        stroke(0.1f);
        c.drawArc(rect(-1.55f, -1.05f, 1.45f, 1.2f), 195, 150, false, paint);
        paint.setStyle(Paint.Style.FILL);
        word(c, "D+", SERIF, 1.75f, -0.05f);
    }

    private static void plex(Canvas c) {
        c.drawPath(poly(-0.62f, -1f, -0.02f, -1f, 0.68f, 0f, -0.02f, 1f, -0.62f, 1f, 0.08f, 0f), paint);
    }

    // --- interface icons

    private static void tv(Canvas c) {
        // Screen outline with a small stand
        stroke(0.17f);
        float r = 0.2f * u;
        c.drawRoundRect(rect(-1.25f, -0.88f, 1.25f, 0.62f), r, r, paint);
        c.drawLine(x(-0.55f), y(0.98f), x(0.55f), y(0.98f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Four tiles drawn like the sketched logos: doubled outlines, light hatching and construction ticks. */
    private static void apps(Canvas c) {
        int color = paint.getColor();
        float r = 0.2f * u;
        float[] o = {-0.92f, 0.12f};
        for (float ox : o) {
            for (float oy : o) {
                RectF tile = rect(ox, oy, ox + 0.8f, oy + 0.8f);
                // Hatching, clipped to the tile
                c.save();
                Path clip = new Path();
                clip.addRoundRect(tile, r, r, Path.Direction.CW);
                c.clipPath(clip);
                stroke(0.035f);
                paint.setAlpha(Color.alpha(color) * 45 / 100);
                for (float d = -0.8f; d <= 0.8f; d += 0.16f) {
                    c.drawLine(x(ox + d), y(oy + 0.8f), x(ox + d + 0.8f), y(oy), paint);
                }
                c.restore();
                // Two slightly offset outlines, like a pencil going over the edge twice
                paint.setAlpha(Color.alpha(color));
                stroke(0.06f);
                c.drawRoundRect(tile, r, r, paint);
                paint.setAlpha(Color.alpha(color) * 60 / 100);
                stroke(0.035f);
                c.drawRoundRect(rect(ox + 0.035f, oy - 0.025f, ox + 0.82f, oy + 0.78f), r, r, paint);
                // Construction ticks past two corners
                c.drawLine(x(ox - 0.12f), y(oy), x(ox + 0.12f), y(oy), paint);
                c.drawLine(x(ox + 0.8f), y(oy + 0.68f), x(ox + 0.8f), y(oy + 0.92f), paint);
            }
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    private static void search(Canvas c) {
        stroke(0.2f);
        c.drawCircle(x(-0.18f), y(-0.18f), 0.6f * u, paint);
        c.drawLine(x(0.28f), y(0.28f), x(0.85f), y(0.85f), paint);
    }

    private static void store(Canvas c) {
        stroke(0.18f);
        c.drawPath(poly(-0.55f, -0.85f, 0.88f, 0f, -0.55f, 0.85f), paint);
    }

    private static void settings(Canvas c) {
        Path gear = circle(0, 0, 0.66f);
        for (int i = 0; i < 8; i++) {
            Path tooth = new Path();
            float rr = 0.08f * u;
            tooth.addRoundRect(rect(-0.17f, -1f, 0.17f, -0.45f), rr, rr, Path.Direction.CW);
            Matrix m = new Matrix();
            m.setRotate(i * 45, cx, cy);
            tooth.transform(m);
            gear.op(tooth, Path.Op.UNION);
        }
        gear.op(circle(0, 0, 0.28f), Path.Op.DIFFERENCE);
        c.drawPath(gear, paint);
    }

    // --- weather

    private static void sun(Canvas c, float ox, float oy, float scale) {
        c.drawCircle(x(ox), y(oy), 0.42f * scale * u, paint);
        stroke(0.13f * scale);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(x(ox + cos * 0.66f * scale), y(oy + sin * 0.66f * scale),
                    x(ox + cos * 0.92f * scale), y(oy + sin * 0.92f * scale), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static void moon(Canvas c) {
        Path p = circle(0, 0, 0.78f);
        p.op(circle(0.42f, -0.32f, 0.62f), Path.Op.DIFFERENCE);
        c.drawPath(p, paint);
    }

    private static Path cloud(float ox, float oy, float s) {
        Path p = circle(ox - 0.48f * s, oy + 0.16f * s, 0.4f * s);
        p.op(circle(ox + 0.02f * s, oy - 0.14f * s, 0.55f * s), Path.Op.UNION);
        p.op(circle(ox + 0.52f * s, oy + 0.2f * s, 0.36f * s), Path.Op.UNION);
        Path base = new Path();
        float r = 0.2f * s * u;
        base.addRoundRect(rect(ox - 0.88f * s, oy + 0.06f * s, ox + 0.88f * s, oy + 0.56f * s), r, r, Path.Direction.CW);
        p.op(base, Path.Op.UNION);
        return p;
    }

    private static void fog(Canvas c) {
        stroke(0.15f);
        c.drawLine(x(-0.85f), y(-0.45f), x(0.85f), y(-0.45f), paint);
        c.drawLine(x(-0.6f), y(0f), x(0.95f), y(0f), paint);
        c.drawLine(x(-0.85f), y(0.45f), x(0.7f), y(0.45f), paint);
    }

    private static void precipitation(Canvas c, boolean snow) {
        c.drawPath(cloud(0, -0.3f, 0.95f), paint);
        for (int i = -1; i <= 1; i++) {
            float px = i * 0.5f;
            if (snow) {
                c.drawCircle(x(px), y(0.72f), 0.11f * u, paint);
            } else {
                stroke(0.13f);
                c.drawLine(x(px + 0.1f), y(0.5f), x(px - 0.08f), y(0.92f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    private static void storm(Canvas c) {
        c.drawPath(cloud(0, -0.35f, 0.95f), paint);
        c.drawPath(poly(0.1f, 0.25f, -0.3f, 0.7f, 0f, 0.7f, -0.15f, 1.05f, 0.35f, 0.55f, 0.05f, 0.55f), paint);
    }
}
