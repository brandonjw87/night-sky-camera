package com.brandon.nightsky;

import android.content.Context;

import com.brandon.nightsky.processing.BrightStars;

import java.io.IOException;
import java.io.InputStream;

/** The bright-star catalogue from the app's assets, read once and kept. */
final class SkyCatalog {
    private static BrightStars stars;
    private static boolean failed;

    private SkyCatalog() {}

    /** The catalogue, or null if it can't be read. Slow the first time (call off the main thread). */
    static synchronized BrightStars get(Context c) {
        if (stars != null || failed) return stars;
        try {
            InputStream in = c.getApplicationContext().getAssets().open("stars.csv");
            try {
                stars = BrightStars.load(in);
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            failed = true;
            android.util.Log.w("NightSky", "star catalogue: " + e);
        }
        return stars;
    }
}
