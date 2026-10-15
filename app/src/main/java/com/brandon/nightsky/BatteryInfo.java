package com.brandon.nightsky;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** The battery right now: charge, temperature and whether it is charging. */
final class BatteryInfo {
    /** Charge in percent, -1 if unknown. */
    int pct = -1;
    float tempC = Float.NaN;
    boolean charging;

    static BatteryInfo read(Context c) {
        BatteryInfo b = new BatteryInfo();
        Intent i = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return b;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        b.pct = level >= 0 && scale > 0 ? Math.round(100f * level / scale) : -1;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        b.tempC = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        b.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        return b;
    }
}
