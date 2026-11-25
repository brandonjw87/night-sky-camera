package com.brandon.nightsky;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Android 17's local network permission (3.0). From Android 17 an app that targets it must hold
 * ACCESS_LOCAL_NETWORK to accept connections from, or connect to, other devices on the Wi-Fi or a hotspot: without
 * it the remote viewer's address would answer nobody (connections just time out). It sits in the Nearby devices
 * group, so a phone that already allowed Bluetooth for the watch has it without being asked again. The internet
 * (the watch's relay, the forecast, the satellites' data) and Bluetooth itself don't need it.
 */
final class LocalNet {
    static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    /** The first Android that asks for it. */
    static final int SDK = 37;

    private LocalNet() {}

    /** Whether the app may use the local network (always, before Android 17). */
    static boolean allowed(Context c) {
        return android.os.Build.VERSION.SDK_INT < SDK || c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** What to tell Brandon when it's refused. */
    static final String REFUSED = "The viewer needs Android's Nearby devices permission to talk on the Wi-Fi: allow it in "
            + "Settings > Apps > Night Sky > Permissions > Nearby devices";
}
