package com.brandon.nightsky.watch;

/**
 * The watch app's resources, for the tests: they run inside the phone app (one Robolectric app), so the phone's own
 * drawables stand in for the watch's copies of them.
 */
public final class R {
    private R() {}

    public static final class drawable {
        public static final int ic_notification = com.brandon.nightsky.R.drawable.ic_notification;
    }
}
