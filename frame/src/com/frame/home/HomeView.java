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
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.TextureView;
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
    private static final String[] TOP_BUTTONS = {"search", "store", "apps", "settings"};
    private static final int MAX_DOCK = 10;

    private static final long WALLPAPER_INTERVAL = 10 * 60 * 1000L;
    private static final long WEATHER_INTERVAL = 15 * 60 * 1000L;
    private static final long NEWS_INTERVAL = 20 * 60 * 1000L;
    private static final long HEADLINE_INTERVAL = 15 * 1000L;

    // Design canvas and layout, in 1920x1080 pixels
    private static final float W = 1920f, H = 1080f, MARGIN = 96f;
    // macOS-style dock: icons only, the focused one magnified (MAG) with neighbours easing off (SPREAD)
    private static final float ICON = 92f, SLOT = 112f, DOCK_MAX_W = 1720f, DOCK_H = 124f, DOCK_PAD = 16f, DOCK_BOTTOM = 64f;
    private static final float MAG = 0.6f, SPREAD = 1.05f;
    private static final float BTN = 64f, BTN_GAP = 10f, BTN_PAD = 8f, TOP_Y = 70f;
    private static final float NEWS_W = W / 3, NEWS_TOP = 290f;   // headline starts just under the weather line
    private static final int APP_COLS = 6, APP_ROWS = 3;
    private static final RectF SCREEN = new RectF(0, 0, W, H);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Wallpapers wallpapers;
    private final Sounds sounds;
    private final Aerials aerials;
    private Bitmap aerialBlur;      // latest blurred video frame for the glass
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
    private long focusTime;   // when the dock focus last moved; drives the wiggle
    private ValueAnimator focusAnim;

    private final News news = new News();
    private List<News.Item> headlines = new ArrayList<>();
    private String newsCountry;      // country the current headlines were fetched for
    private int headlineIndex;
    private float headlineAlpha = 1f;
    private boolean newsFocused;
    private StaticLayout headlineLayout;
    private final TextPaint headlinePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private boolean settingsOpen;
    private int settingsRow;

    private boolean appsOpen;
    private final List<AppEntry> apps = new ArrayList<>();
    private int appsIndex, appsFirstRow;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Typeface light, regular, medium;
    private final Paint topScrim = new Paint(), bottomScrim = new Paint();

    /** A dock entry: an installed app. All apps lives in the top bar. */
    private static final class DockItem {
        final String label, pkg, inputId;   // an app (pkg) or an HDMI input (inputId)

        DockItem(String label, String pkg, String inputId) {
            this.label = label;
            this.pkg = pkg;
            this.inputId = inputId;
        }

        /** How the item is saved, and the key for its chosen icon. */
        String key() {
            return inputId != null ? INPUT_PREFIX + inputId : pkg;
        }
    }

    private static final class AppEntry {
        final String label, pkg, inputId;
        final Drawable banner, icon;

        AppEntry(String label, String pkg, String inputId, Drawable banner, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.inputId = inputId;
            this.banner = banner;
            this.icon = icon;
        }

        String key() {
            return inputId != null ? INPUT_PREFIX + inputId : pkg;
        }
    }

    // --- HDMI inputs

    private static final String INPUT_PREFIX = "input:";
    /** Icons an input can cycle through in edit mode (frame/icons/input.*.png). */
    private static final String[] INPUT_ICONS = {"input.hdmi", "input.playstation", "input.xbox", "input.switch", "input.pc"};

    /** The TV's physical HDMI ports (not CEC devices behind them), in port order, hidden ones skipped. */
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> result = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return result;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null && !info.isHidden(getContext())) {
                result.add(info);
            }
        }
        Collections.sort(result, (a, b) -> inputLabel(a).compareToIgnoreCase(inputLabel(b)));
        return result;
    }

    private TvInputInfo findInput(String id) {
        for (TvInputInfo info : hdmiInputs()) if (info.getId().equals(id)) return info;
        return null;
    }

    /** The name set in the TV's input settings (e.g. "PlayStation"), else "HDMI 1". */
    private String inputLabel(TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(getContext());
        return (custom != null && custom.length() > 0 ? custom : info.loadLabel(getContext())).toString();
    }

    /** Chosen icon for a dock item: a saved choice for inputs, else the app's library icon. */
    private String iconId(DockItem item) {
        if (item.inputId == null) return item.pkg;
        return prefs.getString("icon:" + item.key(), INPUT_ICONS[0]);
    }

    private void cycleInputIcon(DockItem item) {
        String current = iconId(item);
        int i = 0;
        while (i < INPUT_ICONS.length && !INPUT_ICONS[i].equals(current)) i++;
        prefs.edit().putString("icon:" + item.key(), INPUT_ICONS[(i + 1) % INPUT_ICONS.length]).apply();
        sounds.nav();
        invalidate();
    }

    public HomeView(Context context) {
        super(context);
        pm = context.getPackageManager();
        prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE);
        wallpapers = new Wallpapers(context);
        sounds = new Sounds(context);
        // Our own sounds replace the system click on this view
        setSoundEffectsEnabled(false);
        aerials = new Aerials(context, new Aerials.Listener() {
            @Override
            public void onBlur(Bitmap blur) {
                aerialBlur = blur;
                invalidate();
            }

            @Override
            public void onVisible(boolean visible) {
                invalidate();
            }

            @Override
            public void onFade() {
                invalidate();
            }
        });
        wallpapers.setSource(prefs.getString("wallSource", Wallpapers.BING));
        weather = new Weather(context);
        light = inter(context, "'wght' 300, 'opsz' 32", "sans-serif-light");
        regular = inter(context, "'wght' 400", "sans-serif");
        medium = inter(context, "'wght' 560", "sans-serif-medium");
        Glyphs.init(context);
        Glyphs.setStyle(iconStyle());
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
        if (aerialsEnabled()) aerials.start();
        // News after the weather, so the location's country is usually known by then
        if (newsEnabled()) handler.postDelayed(newsTick, 3000);
        handler.postDelayed(headlineTick, HEADLINE_INTERVAL);
    }

    void onPause() {
        handler.removeCallbacksAndMessages(null);
        // Never stream video behind an app
        aerials.stop();
    }

    /** Re-reads the weather now, e.g. right after location permission is granted. */
    void refreshWeather() {
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
    }

    void reset() {
        appsOpen = false;
        settingsOpen = false;
        topFocused = false;
        newsFocused = false;
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
                // The location's country may only be known now: refresh news that used the fallback
                if (newsEnabled() && !News.country().equals(newsCountry)) {
                    handler.removeCallbacks(newsTick);
                    handler.post(newsTick);
                }
                invalidate();
            });
            handler.postDelayed(this, WEATHER_INTERVAL);
        }
    };

    private final Runnable newsTick = new Runnable() {
        @Override
        public void run() {
            newsCountry = News.country();
            news.fetch(News.TOPICS[newsTopic()], items -> {
                headlines = items;
                showHeadline(0);
            });
            handler.postDelayed(this, NEWS_INTERVAL);
        }
    };

    /** Next headline every 15 s: fade out, swap, fade in. */
    private final Runnable headlineTick = new Runnable() {
        @Override
        public void run() {
            // Holds still while you're reading it
            if (!newsFocused) stepHeadline(1);
            handler.postDelayed(this, HEADLINE_INTERVAL);
        }
    };

    /** Fades the text out, swaps to the next/previous headline (resizing the panel), fades in. */
    private void stepHeadline(int dir) {
        if (!newsEnabled() || headlines.size() < 2) return;
        final int next = (headlineIndex + dir + headlines.size()) % headlines.size();
        final boolean[] swapped = {false};
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 2f).setDuration(dir == 1 && !newsFocused ? 1200 : 500);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            if (t >= 1f && !swapped[0]) {
                swapped[0] = true;
                showHeadline(next);
            }
            headlineAlpha = Math.abs(1f - t);
            invalidate();
        });
        anim.start();
        if (newsFocused) sounds.nav();
    }

    private void showHeadline(int index) {
        if (headlines.isEmpty()) return;
        headlineIndex = index % headlines.size();
        News.Item item = headlines.get(headlineIndex);
        headlinePaint.setTypeface(medium);
        headlinePaint.setTextSize(26);
        headlinePaint.setColor(0xFFFFFFFF);
        headlinePaint.setShadowLayer(10f, 0f, 1f, 0x59000000);
        int width = (int) NEWS_W;
        headlineLayout = StaticLayout.Builder.obtain(item.title, 0, item.title.length(), headlinePaint, width)
                .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4f, 1f).build();
        invalidate();
    }

    private boolean newsVisible() {
        return newsEnabled() && !headlines.isEmpty() && headlineLayout != null;
    }

    private void openHeadline() {
        String link = headlines.get(headlineIndex).link;
        if (link == null || !start(new Intent(Intent.ACTION_VIEW, Uri.parse(link)))) {
            Toast.makeText(getContext(), "No browser installed to open the article", Toast.LENGTH_SHORT).show();
        }
    }

    private static final String[] ICON_STYLES = {"macOS", "White", "Color"};

    private static final String[] WALLS = {Wallpapers.BING, Wallpapers.NASA, Wallpapers.UNSPLASH, Wallpapers.PEXELS, Wallpapers.MIXED};
    private static final String[] WALL_NAMES = {"Bing", "NASA", "Unsplash", "Pexels", "Mixed"};

    private int wallIndex() {
        String s = prefs.getString("wallSource", Wallpapers.BING);
        for (int i = 0; i < WALLS.length; i++) if (WALLS[i].equals(s)) return i;
        return 0;
    }

    private boolean wallUsable(String s) {
        return s.equals(Wallpapers.MIXED) || wallpapers.available(s);
    }

    /** Points the wallpapers at the chosen source; when switching, fetches and shows one right away. */
    private void applyWallSource(boolean switched) {
        wallpapers.setSource(WALLS[wallIndex()]);
        if (!switched) return;
        wallpapers.refresh(() -> wallpapers.next(this::show));
        wallpapers.next(this::show);
    }

    private int iconStyle() {
        return Math.min(prefs.getInt("iconStyle", Glyphs.STYLE_MAC), ICON_STYLES.length - 1);
    }

    /** Inter, the open font closest to SF Pro (which can't be bundled outside Apple platforms). */
    private static Typeface inter(Context context, String variation, String fallback) {
        try {
            Typeface tf = new Typeface.Builder(context.getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings(variation).build();
            if (tf != null) return tf;
        } catch (RuntimeException ignored) {
            // Font missing from the APK
        }
        return Typeface.create(fallback, Typeface.NORMAL);
    }

    private boolean newsEnabled() {
        return prefs.getBoolean("news", true);
    }

    private int newsTopic() {
        return Math.min(prefs.getInt("newsTopic", 0), News.TOPICS.length - 1);
    }

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

    /** The saved dock (or the default), installed apps only. */
    private void loadDock() {
        if (editing) return;
        String saved = prefs.getString("packages", null);
        String[] packages = saved == null ? DEFAULT_DOCK : TextUtils.split(saved, ",");
        dock.clear();
        for (String key : packages) {
            if (key.startsWith(INPUT_PREFIX)) {
                TvInputInfo info = findInput(key.substring(INPUT_PREFIX.length()));
                if (info != null) dock.add(new DockItem(inputLabel(info), null, info.getId()));
            } else if (!key.isEmpty() && launchIntent(key) != null) {
                dock.add(new DockItem(label(key), key, null));
            }
        }
        dockIndex = Math.max(0, Math.min(dockIndex, dock.size() - 1));
        focusX = dockIndex;
    }

    private void saveDock() {
        List<String> packages = new ArrayList<>();
        for (DockItem item : dock) packages.add(item.key());
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
        return dock.size();
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
        drawHeadline(c);
        if (appsOpen) drawApps(c);
        if (settingsOpen) drawSettings(c);
        c.restore();
    }

    private void drawWallpaper(Canvas c) {
        if (current == null) {
            c.drawColor(0xFF15191E);
            return;
        }
        float photo = 1f - aerials.view.getAlpha();
        if (photo <= 0f) return;
        if (previous != null) drawLayer(c, previous.image, previous.src, (int) (255 * photo));
        drawLayer(c, current.image, current.src, (int) (255 * fade * photo));
    }

    TextureView aerialView() {
        return aerials.view;
    }

    private boolean aerialsEnabled() {
        return prefs.getBoolean("aerials", false);
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
        if (aerialBlur != null && aerials.view.getAlpha() > 0f) {
            bitmapPaint.setAlpha((int) (255 * aerials.view.getAlpha()));
            c.drawBitmap(aerialBlur, null, SCREEN, bitmapPaint);
        }
        // As clear as possible while staying readable, like macOS vibrancy
        fill.setColor(0x08000000);
        c.drawRect(r, fill);
        fill.setColor(0x14FFFFFF);
        c.drawRect(r, fill);
        c.restore();
        line.setColor(0x40FFFFFF);
        c.drawRoundRect(r, radius, radius, line);
    }

    private void drawClock(Canvas c) {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
        text.setShadowLayer(18f, 0f, 2f, 0x66000000);
        drawText(c, new SimpleDateFormat(pattern, Locale.ENGLISH).format(now), MARGIN - 5, 160, 90, light, 0xFFFFFFFF, Paint.Align.LEFT);
        drawText(c, new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now), MARGIN, 204, 26, regular, 0xF0FFFFFF, Paint.Align.LEFT);
        if (report != null) {
            Glyphs.draw(c, report.icon, MARGIN + 15, 240, 28, 0xF0FFFFFF);
            String weatherText = report.temperature + "°" + (report.place.isEmpty() ? "" : "  " + report.place);
            drawText(c, weatherText, MARGIN + 40, 249, 23, regular, 0xE6FFFFFF, Paint.Align.LEFT);
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
            if (iconStyle() == Glyphs.STYLE_MAC) {
                // Vector SF Symbols-style glyphs: crisp at any size; focus is a white disc with a dark glyph
                if (focused) {
                    fill.setColor(0xEBFFFFFF);
                    c.drawCircle(cx, cy, BTN / 2, fill);
                }
                Glyphs.draw(c, "sf." + TOP_BUTTONS[i], cx, cy, 30, focused ? 0xFF1D1D1F : 0xF2FFFFFF);
                continue;
            }
            if (focused) {
                fill.setColor(0xF2FFFFFF);
                c.drawCircle(cx, cy, BTN / 2, fill);
            }
            Glyphs.draw(c, TOP_BUTTONS[i], cx, cy, 30, focused ? 0xFF15181C : 0xEEFFFFFF);
        }
    }

    /** Slot width at rest; shrinks when a full dock would not fit the screen. */
    private float slotW() {
        return Math.min(SLOT, (DOCK_MAX_W - DOCK_PAD * 2) / Math.max(1, dock.size()));
    }

    /** Magnification of item i: a bell curve around the (animated) focus, as on the macOS dock. */
    private float scaleAt(int i) {
        if (topFocused || newsFocused || appsOpen || settingsOpen) return 1f;
        float d = i - focusX;
        return 1f + MAG * (float) Math.exp(-(d * d) / (2 * SPREAD * SPREAD));
    }

    /** The dock at rest: what the news panel lines up with, so it doesn't move with the magnify. */
    private RectF dockRect() {
        float w = dock.size() * slotW() + DOCK_PAD * 2;
        float left = (W - w) / 2, top = H - DOCK_BOTTOM - DOCK_H;
        return new RectF(left, top, left + w, top + DOCK_H);
    }

    private void drawDock(Canvas c) {
        RectF rest = dockRect();
        float slot = slotW(), icon = ICON * slot / SLOT;
        // Magnified items take more room; the dock widens around its centre
        float[] scale = new float[dock.size()];
        float width = DOCK_PAD * 2;
        for (int i = 0; i < dock.size(); i++) {
            scale[i] = scaleAt(i);
            width += slot * scale[i];
        }
        RectF r = new RectF((W - width) / 2, rest.top, (W + width) / 2, rest.bottom);
        glass(c, r, 36);
        float x = r.left + DOCK_PAD, base = r.bottom - DOCK_PAD;
        boolean dockFocused = !topFocused && !newsFocused && !appsOpen;
        for (int i = 0; i < dock.size(); i++) {
            DockItem item = dock.get(i);
            float w = slot * scale[i], size = icon * scale[i], cx = x + w / 2;
            boolean focused = dockFocused && i == dockIndex;
            float lift = focused && editing ? 14 : 0;
            int alpha = editing && !focused ? 0x80 : 0xFF;
            RectF box = new RectF(cx - size / 2, base - size - lift, cx + size / 2, base - lift);
            boolean wiggle = focused && !editing;
            if (wiggle) wiggle(c, box);
            if (!Glyphs.drawFit(c, iconId(item), box, (alpha << 24) | 0xFFFFFF)) tile(c, item.label, box, alpha);
            if (wiggle) c.restore();
            x += w;
        }
        if (editing && dockFocused) {
            text.setShadowLayer(12f, 0f, 2f, 0x80000000);
            String hint = dock.get(dockIndex).inputId != null
                    ? "◀ ▶  Move      ▲  Icon      ▼  Remove      OK  Done"
                    : "◀ ▶  Move      ▼  Remove      OK  Done";
            drawText(c, hint, W / 2, base - icon * (1 + MAG) - 40, 24, medium, 0xF2FFFFFF, Paint.Align.CENTER);
            text.clearShadowLayer();
        }
    }

    /**
     * Wiggle for the focused icon: a quick sway that settles after landing on it, then a slow,
     * barely-there sway so the selection stays easy to spot. Pivots near the icon's base.
     */
    private void wiggle(Canvas c, RectF box) {
        float t = (SystemClock.uptimeMillis() - focusTime) / 1000f;
        float land = (float) Math.exp(-t * 2.6f);                       // fades over ~1 s
        float angle = (float) (7f * land * Math.sin(t * 2 * Math.PI * 3.2f)      // landing sway
                + 1.6f * Math.sin(t * 2 * Math.PI * 0.55f));                       // idle sway
        float dy = (float) (-4f * land * Math.abs(Math.sin(t * 2 * Math.PI * 3.2f)));
        c.save();
        c.translate(0, dy);
        c.rotate(angle, box.centerX(), box.bottom - box.height() * 0.15f);
        postInvalidateOnAnimation();
    }

    /** Stand-in for apps without a library icon: a frosted tile with the app's initial. */
    private void tile(Canvas c, String label, RectF box, int alpha) {
        float radius = box.width() * 0.23f;
        fill.setColor((alpha * 0x40 / 0xFF) << 24 | 0xFFFFFF);
        c.drawRoundRect(box, radius, radius, fill);
        String initial = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.ROOT);
        drawText(c, initial, box.centerX(), box.centerY() + box.height() * 0.17f, box.height() * 0.48f, medium,
                (alpha << 24) | 0xFFFFFF, Paint.Align.CENTER);
    }

    /**
     * Headline as plain text under the weather, a third of the screen wide. Its top is fixed just
     * below the weather line, so longer headlines only grow downward.
     */
    private void drawHeadline(Canvas c) {
        if (!newsVisible() || editing || appsOpen || settingsOpen) return;
        News.Item item = headlines.get(headlineIndex);
        float top = NEWS_TOP, x = MARGIN;
        headlinePaint.setAlpha((int) (headlineAlpha * 0xF2));
        c.save();
        c.translate(x, top);
        headlineLayout.draw(c);
        c.restore();
        float metaY = top + headlineLayout.getHeight() + 28;
        String meta = item.source + (item.time > 0 ? " · " + age(item.time) : "")
                + (newsFocused ? "     ◀ ▶  more   ·   OK  read" : "");
        text.setShadowLayer(10f, 0f, 1f, 0x59000000);
        drawText(c, meta, x, metaY, 21, regular, ((int) (headlineAlpha * 0xB3) << 24) | 0xFFFFFF, Paint.Align.LEFT);
        text.clearShadowLayer();
        if (newsFocused) {
            // No box to highlight: a slim accent bar beside the selected headline
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(new RectF(x - 22, top + 4, x - 17, metaY + 6), 3, 3, fill);
        }
    }

    private static String age(long time) {
        long min = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (min < 60) return min + " min ago";
        if (min < 48 * 60) return (min / 60) + " h ago";
        return (min / 1440) + " days ago";
    }

    private void drawSettings(Canvas c) {
        fill.setColor(0x99000000);
        c.drawRect(SCREEN, fill);
        RectF p = new RectF(W / 2 - 420, H / 2 - 318, W / 2 + 420, H / 2 + 318);
        glass(c, p, 44);
        drawText(c, "Frame settings", p.left + 56, p.top + 84, 38, medium, 0xFFFFFFFF, Paint.Align.LEFT);
        String[] labels = {"News headlines", "News topic", "Icon style", "Wallpaper"};
        String[] values = {newsEnabled() ? "On" : "Off", News.TOPIC_NAMES[newsTopic()], ICON_STYLES[iconStyle()],
                aerialsEnabled() ? "Apple Aerials" : WALL_NAMES[wallIndex()]};
        for (int i = 0; i < labels.length; i++) {
            RectF row = new RectF(p.left + 32, p.top + 130 + i * 96, p.right - 32, p.top + 210 + i * 96);
            boolean focused = i == settingsRow;
            boolean dim = i == 1 && !newsEnabled();
            if (focused) {
                fill.setColor(0x38FFFFFF);
                c.drawRoundRect(row, 28, 28, fill);
            }
            int color = dim ? 0x66FFFFFF : 0xF2FFFFFF;
            drawText(c, labels[i], row.left + 28, row.centerY() + 11, 30, focused ? medium : regular, color, Paint.Align.LEFT);
            drawText(c, (focused ? "‹  " : "") + values[i] + (focused ? "  ›" : ""), row.right - 28, row.centerY() + 11, 30, medium, color, Paint.Align.RIGHT);
        }
        String country = new Locale("", News.country()).getDisplayCountry(Locale.ENGLISH);
        String source = (Weather.country != null ? "Location: " : "TV region: ") + country;
        drawText(c, "News from Google News · " + source, p.left + 60, p.bottom - 40, 24, regular, 0x99FFFFFF, Paint.Align.LEFT);
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
                if (app.inputId != null) {
                    // Inputs have no app icon: a dark tile with the HDMI glyph
                    fill.setColor(0x66000000);
                    c.drawRoundRect(new RectF(cx - 112, y + 22, cx + 112, y + 148), 14, 14, fill);
                    Glyphs.draw(c, INPUT_ICONS[0], cx, y + 85, 64, 0xF2FFFFFF);
                }
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
        if (settingsOpen) {
            settingsKey(k);
            return true;
        }
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
                else if (newsFocused) stepHeadline(-1);
                else moveDockFocus(dockIndex - 1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (topFocused) moveTopFocus(topIndex + 1);
                else if (newsFocused) stepHeadline(1);
                else moveDockFocus(dockIndex + 1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                // Dock -> news panel (when shown) -> top bar
                if (topFocused) break;
                if (!newsFocused && newsVisible()) newsFocused = true;
                else {
                    newsFocused = false;
                    topFocused = true;
                }
                sounds.nav();
                invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (topFocused || newsFocused) {
                    topFocused = false;
                    newsFocused = false;
                    focusTime = SystemClock.uptimeMillis();
                    sounds.nav();
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
        if (settingsOpen) {
            changeSetting(1);
        } else if (appsOpen) {
            if (!apps.isEmpty()) {
                appsOpen = false;
                invalidate();
                open(apps.get(appsIndex).pkg, apps.get(appsIndex).inputId);
            }
        } else if (editing) {
            finishEditing();
        } else if (newsFocused) {
            openHeadline();
        } else {
            activate();
        }
    }

    private void longPress() {
        if (settingsOpen) return;
        if (topFocused && TOP_BUTTONS[topIndex].equals("settings")) {
            sounds.click();
            settingsOpen = true;
            settingsRow = 0;
            invalidate();
        } else if (appsOpen) {
            if (!apps.isEmpty()) addToDock(apps.get(appsIndex));
        } else if (!editing && !topFocused && !newsFocused && !dock.isEmpty()) {
            editing = true;
            sounds.nav();
            invalidate();
        }
    }

    // --- settings panel (hold OK on the gear)

    private void settingsKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: settingsRow = Math.max(0, settingsRow - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: settingsRow = Math.min(3, settingsRow + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: changeSetting(-1); return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: changeSetting(1); return;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                settingsOpen = false;
                break;
            default: return;
        }
        sounds.nav();
        invalidate();
    }

    private void changeSetting(int dir) {
        if (settingsRow == 0) {
            boolean on = !newsEnabled();
            prefs.edit().putBoolean("news", on).apply();
            handler.removeCallbacks(newsTick);
            if (on) handler.post(newsTick);
            else headlines = new ArrayList<>();
        } else if (settingsRow == 3) {
            // Photo sources, then Apple Aerials (local build only) as one more stop in the cycle
            int n = WALLS.length + 1;
            int i = aerialsEnabled() ? WALLS.length : wallIndex();
            do i = (i + dir + n) % n; while (i < WALLS.length && !wallUsable(WALLS[i]));
            boolean aerial = i == WALLS.length;
            prefs.edit().putBoolean("aerials", aerial).apply();
            if (aerial) {
                aerials.start();
            } else {
                aerials.stop();
                prefs.edit().putString("wallSource", WALLS[i]).apply();
                applyWallSource(true);
            }
        } else if (settingsRow == 2) {
            int n = ICON_STYLES.length;
            prefs.edit().putInt("iconStyle", (iconStyle() + dir + n) % n).apply();
            Glyphs.setStyle(iconStyle());
        } else if (newsEnabled()) {
            int n = News.TOPICS.length;
            prefs.edit().putInt("newsTopic", (newsTopic() + dir + n) % n).apply();
            handler.removeCallbacks(newsTick);
            handler.post(newsTick);
        }
        sounds.nav();
        invalidate();
    }

    // --- editing the dock

    private void editKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: moveDockItem(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveDockItem(1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: removeDockItem(); break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (dock.get(dockIndex).inputId != null) cycleInputIcon(dock.get(dockIndex));
                break;
            case KeyEvent.KEYCODE_BACK: finishEditing(); break;
            default: break;
        }
    }

    /** Swaps the focused app with its neighbour. */
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
            if (app.key().equals(item.key())) {
                Toast.makeText(getContext(), app.label + " is already in the dock", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (dockApps() >= MAX_DOCK) {
            Toast.makeText(getContext(), "The dock is full. Remove an app first.", Toast.LENGTH_SHORT).show();
            return;
        }
        dock.add(dockApps(), app.inputId != null ? new DockItem(app.label, null, app.inputId)
                : new DockItem(label(app.pkg), app.pkg, null));
        saveDock();
        Toast.makeText(getContext(), app.label + " added to the dock", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private void moveTopFocus(int i) {
        if (i < 0 || i >= TOP_BUTTONS.length) return;
        topIndex = i;
        sounds.nav();
        invalidate();
    }

    private void moveDockFocus(int i, boolean animate) {
        if (i < 0 || i >= dock.size()) return;
        dockIndex = i;
        focusTime = SystemClock.uptimeMillis();
        if (focusAnim != null) focusAnim.cancel();
        if (!animate) {
            focusX = i;
            invalidate();
            return;
        }
        sounds.nav();
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
                case "apps": openApps(); break;
                default: start(new Intent(Settings.ACTION_SETTINGS)); break;
            }
            return;
        }
        if (dock.isEmpty()) return;
        DockItem item = dock.get(dockIndex);
        open(item.pkg, item.inputId);
    }

    // --- all apps panel

    private void openApps() {
        sounds.click();
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
                apps.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, null, banner, ri.loadIcon(pm)));
            }
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        // HDMI inputs first, so they can be added to the dock like apps
        List<TvInputInfo> inputs = hdmiInputs();
        for (int i = inputs.size() - 1; i >= 0; i--) {
            apps.add(0, new AppEntry(inputLabel(inputs.get(i)), null, inputs.get(i).getId(), null, null));
        }
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
        if (i != appsIndex) sounds.nav();
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

    /** Opens an app, or switches the TV to an HDMI input through its live TV app. */
    private void open(String pkg, String inputId) {
        if (inputId == null) {
            launch(pkg);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
        if (!start(i)) Toast.makeText(getContext(), "Can't switch to this input", Toast.LENGTH_SHORT).show();
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
        sounds.click();
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
