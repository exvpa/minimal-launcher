package com.frame.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The launcher's own UI sounds (assets/sounds, made by frame/sounds/make_sounds.py),
 * so they stay soft regardless of the TV's system sound theme.
 */
final class Sounds {
    private final SoundPool pool;
    private final int nav, click;

    Sounds(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        nav = load(context, "sounds/nav.wav");
        click = load(context, "sounds/click.wav");
    }

    /** Copied to the cache first: openFd needs uncompressed APK entries, which the build doesn't guarantee. */
    private int load(Context context, String asset) {
        File file = new File(context.getCacheDir(), asset.replace('/', '_'));
        try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            return 0;
        }
        return pool.load(file.getPath(), 1);
    }

    /** Moving focus. */
    void nav() {
        if (nav != 0) pool.play(nav, 1f, 1f, 1, 0, 1f);
    }

    /** Opening an app, a panel or an article. */
    void click() {
        if (click != 0) pool.play(click, 1f, 1f, 1, 0, 1f);
    }
}
