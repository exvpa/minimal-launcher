package com.frame.home;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The whole home screen, drawn in one view on a 1920x1080 design canvas:
 * photo, clock/date/weather (top left), search/store/settings (top right)
 * and a frosted-glass dock of favourite apps. Focus is tracked here rather
 * than with Android's view focus, so remote navigation is fully predictable.
 */
public class HomeView extends View {
    /** Dock until the user edits it; packages that aren't installed are skipped. */
    private static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.apple.atve.androidtv.appletv",
            "com.disney.disneyplus",
            "com.plexapp.android",
            "com.wbd.stream",
            "com.skyshowtime.skyshowtime.google",
            // Live TV: TCL and Sony versions
            "com.tcl.tv",
            "com.sony.dtv.tvx",
    };
    private static final String[] TOP_BUTTONS = {"search", "store", "settings"};
    private static final int MAX_DOCK = 10;

    private static final long WALLPAPER_INTERVAL = 10 * 60 * 1000L;
    private static final long WEATHER_INTERVAL = 15 * 60 * 1000L;

    // Design canvas and layout, in 1920x1080 pixels
    private static final float W = 1920f, H = 1080f, MARGIN = 96f;
    private static final float ITEM_W = 190f, DOCK_MAX_W = 1720f, DOCK_H = 190f, DOCK_PAD = 14f, DOCK_BOTTOM = 76f;
    private static final float BTN = 64f, BTN_GAP = 10f, BTN_PAD = 8f, TOP_Y = 70f;
    private static final int APP_COLS = 6, APP_ROWS = 3;
    private static final RectF SCREEN = new RectF(0, 0, W, H);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Wallpapers wallpapers;
    private final Weather weather;
    private final PackageManager pm;

    private final SharedPreferences prefs;
    private final List<DockItem> dock = new ArrayList<>();
    private boolean editing;        // moving/removing the focused dock app
    private boolean longPressed;    // OK is held and its long press already acted
    private Wallpapers.Frame current, previous;
    private float fade = 1f;
    private long lastWallpaperChange;
    private Weather.Report report;

    private boolean topFocused;
    private int dockIndex, topIndex;
    private float focusX;
    private ValueAnimator focusAnim;

    private boolean appsOpen;
    private final List<AppEntry> apps = new ArrayList<>();
    private int appsIndex, appsFirstRow;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Paint topScrim = new Paint(), bottomScrim = new Paint();

    /** A dock entry. A null package is the Apps button, always last. */
    private static final class DockItem {
        final String label, pkg;

        DockItem(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }

        String iconId() {
            return pkg == null ? "apps" : pkg;
        }
    }

    private static final class AppEntry {
        final String label, pkg;
        final Drawable banner, icon;

        AppEntry(String label, String pkg, Drawable banner, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.banner = banner;
            this.icon = icon;
        }
    }

    public HomeView(Context context) {
        super(context);
        pm = context.getPackageManager();
        prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE);
        wallpapers = new Wallpapers(context);
        weather = new Weather(context);
        Glyphs.init(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f);
        topScrim.setShader(new RadialGradient(0, 0, 1100, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        bottomScrim.setShader(new LinearGradient(0, 640, 0, H, 0x00000000, 0x73000000, Shader.TileMode.CLAMP));
        loadDock();
    }

    // --- lifecycle

    void onResume() {
        Glyphs.reload();
        loadDock();
        handler.removeCallbacksAndMessages(null);
        handler.post(clockTick);
        if (current == null || System.currentTimeMillis() - lastWallpaperChange > WALLPAPER_INTERVAL) {
            wallpapers.next(this::show);
        }
        handler.postDelayed(rotate, WALLPAPER_INTERVAL);
        wallpapers.refresh(() -> {
            if (current == null) wallpapers.next(this::show);
        });
        handler.post(weatherTick);
    }

    void onPause() {
        handler.removeCallbacksAndMessages(null);
    }

    /** Re-reads the weather now, e.g. right after location permission is granted. */
    void refreshWeather() {
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
    }

    void reset() {
        appsOpen = false;
        topFocused = false;
        if (editing) {
            editing = false;
            saveDock();
        }
        moveDockFocus(0, false);
    }

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60000 - now % 60000 + 50);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            wallpapers.next(HomeView.this::show);
            handler.postDelayed(this, WALLPAPER_INTERVAL);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            weather.fetch(r -> {
                report = r;
                invalidate();
            });
            handler.postDelayed(this, WEATHER_INTERVAL);
        }
    };

    private void show(Wallpapers.Frame frame) {
        lastWallpaperChange = System.currentTimeMillis();
        if (current == null) {
            current = frame;
            fade = 1f;
            invalidate();
            return;
        }
        previous = current;
        current = frame;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f).setDuration(1800);
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            if (fade >= 1f) previous = null;
            invalidate();
        });
        anim.start();
    }

    /** The saved dock (or the default), installed apps only, plus the Apps entry. */
    private void loadDock() {
        if (editing) return;
        String saved = prefs.getString("packages", null);
        String[] packages = saved == null ? DEFAULT_DOCK : TextUtils.split(saved, ",");
        dock.clear();
        for (String pkg : packages) {
            if (pkg.isEmpty() || launchIntent(pkg) == null) continue;
            dock.add(new DockItem(label(pkg), pkg));
        }
        dock.add(new DockItem("Apps", null));
        dockIndex = Math.min(dockIndex, dock.size() - 1);
        focusX = dockIndex;
    }

    private void saveDock() {
        List<String> packages = new ArrayList<>();
        for (DockItem item : dock) if (item.pkg != null) packages.add(item.pkg);
        prefs.edit().putString("packages", TextUtils.join(",", packages)).apply();
    }

    private String label(String pkg) {
        if (pkg.equals("com.tcl.tv") || pkg.equals("com.sony.dtv.tvx")) return "TV";
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private int dockApps() {
        return dock.size() - 1;
    }

    // --- drawing

    @Override
    protected void onDraw(Canvas c) {
        float scale = getWidth() / W;
        c.save();
        c.scale(scale, scale);
        drawWallpaper(c);
        c.drawRect(SCREEN, topScrim);
        c.drawRect(SCREEN, bottomScrim);
        drawClock(c);
        drawTopBar(c);
        drawDock(c);
        if (appsOpen) drawApps(c);
        c.restore();
    }

    private void drawWallpaper(Canvas c) {
        if (current == null) {
            c.drawColor(0xFF15191E);
            return;
        }
        if (previous != null) drawLayer(c, previous.image, previous.src, 255);
        drawLayer(c, current.image, current.src, (int) (255 * fade));
    }

    private void drawLayer(Canvas c, Bitmap bmp, Rect src, int alpha) {
        bitmapPaint.setAlpha(alpha);
        c.drawBitmap(bmp, src, SCREEN, bitmapPaint);
    }

    /** Frosted glass: the pre-blurred photo, clipped to the shape, with a light tint and hairline edge. */
    private void glass(Canvas c, RectF r, float radius) {
        c.save();
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        if (current == null) {
            c.drawColor(0xFF2A3038);
        } else {
            if (previous != null) drawLayer(c, previous.blur, null, 255);
            drawLayer(c, current.blur, null, (int) (255 * fade));
        }
        fill.setColor(0x47000000);
        c.drawRect(r, fill);
        fill.setColor(0x12FFFFFF);
        c.drawRect(r, fill);
        c.restore();
        line.setColor(0x2EFFFFFF);
        c.drawRoundRect(r, radius, radius, line);
    }

    private void drawClock(Canvas c) {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
        text.setShadowLayer(18f, 0f, 2f, 0x66000000);
        drawText(c, new SimpleDateFormat(pattern, Locale.ENGLISH).format(now), MARGIN - 6, 178, 120, light, 0xFFFFFFFF, Paint.Align.LEFT);
        drawText(c, new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now), MARGIN, 236, 34, regular, 0xF0FFFFFF, Paint.Align.LEFT);
        if (report != null) {
            Glyphs.draw(c, report.icon, MARGIN + 20, 284, 38, 0xF0FFFFFF);
            String weatherText = report.temperature + "°" + (report.place.isEmpty() ? "" : "  " + report.place);
            drawText(c, weatherText, MARGIN + 54, 297, 30, regular, 0xE6FFFFFF, Paint.Align.LEFT);
        }
        text.clearShadowLayer();
    }

    private RectF topPill() {
        float w = BTN_PAD * 2 + TOP_BUTTONS.length * BTN + (TOP_BUTTONS.length - 1) * BTN_GAP;
        return new RectF(W - MARGIN - w, TOP_Y, W - MARGIN, TOP_Y + BTN + BTN_PAD * 2);
    }

    private void drawTopBar(Canvas c) {
        RectF pill = topPill();
        glass(c, pill, pill.height() / 2);
        for (int i = 0; i < TOP_BUTTONS.length; i++) {
            float cx = pill.left + BTN_PAD + BTN / 2 + i * (BTN + BTN_GAP);
            float cy = pill.centerY();
            boolean focused = topFocused && i == topIndex && !appsOpen;
            if (focused) {
                fill.setColor(0xF2FFFFFF);
                c.drawCircle(cx, cy, BTN / 2, fill);
            }
            Glyphs.draw(c, TOP_BUTTONS[i], cx, cy, 30, focused ? 0xFF15181C : 0xEEFFFFFF);
        }
    }

    /** Item width shrinks when a full dock would not fit the screen. */
    private float itemW() {
        return Math.min(ITEM_W, (DOCK_MAX_W - DOCK_PAD * 2) / dock.size());
    }

    private RectF dockRect() {
        float w = dock.size() * itemW() + DOCK_PAD * 2;
        float left = (W - w) / 2, top = H - DOCK_BOTTOM - DOCK_H;
        return new RectF(left, top, left + w, top + DOCK_H);
    }

    private void drawDock(Canvas c) {
        RectF r = dockRect();
        float itemW = itemW();
        glass(c, r, 44);
        if (!topFocused && !appsOpen) {
            float x = r.left + DOCK_PAD + focusX * itemW;
            RectF tile = new RectF(x + 4, r.top + DOCK_PAD, x + itemW - 4, r.bottom - DOCK_PAD);
            fill.setColor(editing ? 0x52FFFFFF : 0x38FFFFFF);
            c.drawRoundRect(tile, 32, 32, fill);
            if (editing) {
                line.setColor(0xCCFFFFFF);
                line.setStrokeWidth(3f);
                c.drawRoundRect(tile, 32, 32, line);
                line.setStrokeWidth(1.5f);
                text.setShadowLayer(12f, 0f, 2f, 0x80000000);
                drawText(c, "◀ ▶  Move      ▼  Remove      OK  Done", W / 2, r.top - 28, 26, medium, 0xF2FFFFFF, Paint.Align.CENTER);
                text.clearShadowLayer();
            }
        }
        for (int i = 0; i < dock.size(); i++) {
            DockItem item = dock.get(i);
            float cx = r.left + DOCK_PAD + i * itemW + itemW / 2;
            boolean focused = !topFocused && !appsOpen && i == dockIndex;
            int color = focused ? 0xFFFFFFFF : 0xD9FFFFFF;
            float lift = focused && editing ? -8 : 0;
            if (item.pkg == null || Glyphs.hasImage(item.iconId())) {
                Glyphs.draw(c, item.iconId(), cx, r.top + 78 + lift, 56, color);
            } else {
                wordmark(c, item.label, cx, r.top + 78 + lift, itemW - 36, color);
            }
            text.setTextSize(26);
            String label = TextUtils.ellipsize(item.label, text, itemW - 16, TextUtils.TruncateAt.END).toString();
            drawText(c, label, cx, r.top + 152, 26, focused ? medium : regular, color, Paint.Align.CENTER);
        }
    }

    /** Stand-in logo for apps without a library icon: the name, bold, sized to the slot. */
    private void wordmark(Canvas c, String label, float cx, float cy, float maxW, int color) {
        text.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        float size = 44;
        text.setTextSize(size);
        float w = text.measureText(label);
        if (w > maxW) size = Math.max(22, size * maxW / w);
        text.setTextSize(size);
        String s = TextUtils.ellipsize(label, text, maxW, TextUtils.TruncateAt.END).toString();
        drawText(c, s, cx, cy + size * 0.36f, size, text.getTypeface(), color, Paint.Align.CENTER);
    }

    private void drawApps(Canvas c) {
        fill.setColor(0x99000000);
        c.drawRect(SCREEN, fill);
        RectF p = new RectF(160, 110, W - 160, H - 110);
        glass(c, p, 48);
        drawText(c, "All apps", p.left + 64, p.top + 84, 38, medium, 0xFFFFFFFF, Paint.Align.LEFT);
        drawText(c, "Hold OK to add to the dock", p.right - 64, p.top + 82, 26, regular, 0xB3FFFFFF, Paint.Align.RIGHT);
        float cellW = (p.width() - 80) / APP_COLS, cellH = 220, gridTop = p.top + 120;
        for (int row = 0; row < APP_ROWS; row++) {
            for (int col = 0; col < APP_COLS; col++) {
                int i = (appsFirstRow + row) * APP_COLS + col;
                if (i >= apps.size()) return;
                AppEntry app = apps.get(i);
                float x = p.left + 40 + col * cellW, y = gridTop + row * cellH;
                if (i == appsIndex) {
                    fill.setColor(0x38FFFFFF);
                    c.drawRoundRect(new RectF(x + 8, y, x + cellW - 8, y + cellH - 12), 28, 28, fill);
                }
                float cx = x + cellW / 2;
                Drawable d = app.banner != null ? app.banner : app.icon;
                if (d != null) {
                    if (app.banner != null) d.setBounds((int) (cx - 112), (int) (y + 22), (int) (cx + 112), (int) (y + 148));
                    else d.setBounds((int) (cx - 52), (int) (y + 30), (int) (cx + 52), (int) (y + 134));
                    d.draw(c);
                }
                text.setTextSize(24);
                String label = TextUtils.ellipsize(app.label, text, cellW - 32, TextUtils.TruncateAt.END).toString();
                drawText(c, label, cx, y + 186, 24, i == appsIndex ? medium : regular, 0xF0FFFFFF, Paint.Align.CENTER);
            }
        }
    }

    private void drawText(Canvas c, String s, float x, float y, float size, Typeface tf, int color, Paint.Align align) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    // --- remote control

    boolean handleKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ours = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK
                || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SEARCH;
        if (!ours) return false;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // OK acts on release; holding it (key repeat) is a long press instead
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) longPressed = false;
                else if (!longPressed) {
                    longPressed = true;
                    longPress();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!longPressed) shortPress();
                longPressed = false;
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (appsOpen) {
            appsKey(k);
            return true;
        }
        if (editing) {
            editKey(k);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (topFocused) moveTopFocus(topIndex - 1);
                else moveDockFocus(dockIndex - 1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (topFocused) moveTopFocus(topIndex + 1);
                else moveDockFocus(dockIndex + 1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!topFocused) {
                    topFocused = true;
                    playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    invalidate();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (topFocused) {
                    topFocused = false;
                    playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                    invalidate();
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                openApps();
                break;
            case KeyEvent.KEYCODE_SEARCH:
                openSearch();
                break;
            default:
                break;
        }
        return true;
    }

    private void shortPress() {
        if (appsOpen) {
            if (!apps.isEmpty()) {
                appsOpen = false;
                invalidate();
                launch(apps.get(appsIndex).pkg);
            }
        } else if (editing) {
            finishEditing();
        } else {
            activate();
        }
    }

    private void longPress() {
        if (appsOpen) {
            if (!apps.isEmpty()) addToDock(apps.get(appsIndex));
        } else if (!editing && !topFocused && dock.get(dockIndex).pkg != null) {
            editing = true;
            playSoundEffect(SoundEffectConstants.CLICK);
            invalidate();
        }
    }

    // --- editing the dock

    private void editKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: moveDockItem(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveDockItem(1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: removeDockItem(); break;
            case KeyEvent.KEYCODE_BACK: finishEditing(); break;
            default: break;
        }
    }

    /** Swaps the focused app with its neighbour; the Apps button stays last. */
    private void moveDockItem(int dir) {
        int to = dockIndex + dir;
        if (to < 0 || to >= dockApps()) return;
        Collections.swap(dock, dockIndex, to);
        moveDockFocus(to, true);
    }

    private void removeDockItem() {
        DockItem removed = dock.remove(dockIndex);
        editing = false;
        saveDock();
        Toast.makeText(getContext(), removed.label + " removed from the dock", Toast.LENGTH_SHORT).show();
        moveDockFocus(Math.min(dockIndex, dock.size() - 1), false);
    }

    private void finishEditing() {
        editing = false;
        saveDock();
        invalidate();
    }

    private void addToDock(AppEntry app) {
        for (DockItem item : dock) {
            if (app.pkg.equals(item.pkg)) {
                Toast.makeText(getContext(), app.label + " is already in the dock", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (dockApps() >= MAX_DOCK) {
            Toast.makeText(getContext(), "The dock is full. Remove an app first.", Toast.LENGTH_SHORT).show();
            return;
        }
        dock.add(dockApps(), new DockItem(label(app.pkg), app.pkg));
        saveDock();
        Toast.makeText(getContext(), app.label + " added to the dock", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private void moveTopFocus(int i) {
        if (i < 0 || i >= TOP_BUTTONS.length) return;
        topIndex = i;
        playSoundEffect(SoundEffectConstants.CLICK);
        invalidate();
    }

    private void moveDockFocus(int i, boolean animate) {
        if (i < 0 || i >= dock.size()) return;
        dockIndex = i;
        if (focusAnim != null) focusAnim.cancel();
        if (!animate) {
            focusX = i;
            invalidate();
            return;
        }
        playSoundEffect(SoundEffectConstants.CLICK);
        focusAnim = ValueAnimator.ofFloat(focusX, i).setDuration(170);
        focusAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        focusAnim.addUpdateListener(a -> {
            focusX = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    private void activate() {
        if (topFocused) {
            switch (TOP_BUTTONS[topIndex]) {
                case "search": openSearch(); break;
                case "store": launch("com.android.vending"); break;
                default: start(new Intent(Settings.ACTION_SETTINGS)); break;
            }
            return;
        }
        String pkg = dock.get(dockIndex).pkg;
        if (pkg == null) openApps();
        else launch(pkg);
    }

    // --- all apps panel

    private void openApps() {
        apps.clear();
        Set<String> seen = new HashSet<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                apps.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, banner, ri.loadIcon(pm)));
            }
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        appsIndex = 0;
        appsFirstRow = 0;
        appsOpen = true;
        invalidate();
    }

    private void appsKey(int k) {
        int i = appsIndex;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (i % APP_COLS > 0) i--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (i % APP_COLS < APP_COLS - 1 && i + 1 < apps.size()) i++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (i >= APP_COLS) i -= APP_COLS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: i = Math.min(i + APP_COLS, apps.size() - 1); break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                appsOpen = false;
                invalidate();
                return;
            default:
                return;
        }
        if (i != appsIndex) playSoundEffect(SoundEffectConstants.CLICK);
        appsIndex = i;
        int row = i / APP_COLS;
        if (row < appsFirstRow) appsFirstRow = row;
        if (row >= appsFirstRow + APP_ROWS) appsFirstRow = row - APP_ROWS + 1;
        invalidate();
    }

    // --- launching

    private Intent launchIntent(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) {
            Toast.makeText(getContext(), "App not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        start(i);
    }

    private void openSearch() {
        if (!start(new Intent("android.search.action.GLOBAL_SEARCH"))) start(new Intent(Intent.ACTION_ASSIST));
    }

    private boolean start(Intent i) {
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
