package com.brandon.nightsky;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The app's shortcuts (E3), shown when the app icon is long-pressed. The four fixed ones are in
 * res/xml/shortcuts.xml; this adds "Tonight's plan" while a plan for tonight is waiting or armed, and tells the
 * launcher which shortcut was used so it can rank them.
 */
final class Shortcuts {
    private Shortcuts() {}

    /** Shows or hides the plan's shortcut to match {@link Glance}. Any thread. */
    static void update(Context c, Glance g) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            boolean plan = g.planWaiting || g.active && g.next.startsWith("Plan armed");
            if (!plan) {
                sm.removeDynamicShortcuts(Collections.singletonList("plan"));
                return;
            }
            Intent i = new Intent(MainActivity.ACTION_LAUNCH, MainActivity.launchUri("plan")).setClass(c, MainActivity.class);
            List<ShortcutInfo> l = new ArrayList<ShortcutInfo>();
            l.add(new ShortcutInfo.Builder(c, "plan")
                    .setShortLabel(c.getString(R.string.sc_plan_short))
                    .setLongLabel(c.getString(R.string.sc_plan_long))
                    .setIcon(Icon.createWithResource(c, R.drawable.ic_sc_plan))
                    .setIntent(i)
                    .setRank(0)
                    .build());
            sm.setDynamicShortcuts(l);
        } catch (RuntimeException e) {
            // rate-limited or the launcher said no: the fixed shortcuts still work
            android.util.Log.w("NightSky", "shortcuts: " + e);
        }
    }

    /** A shortcut (or the same action from elsewhere) was used: the launcher ranks it higher. */
    static void used(Context c, String id) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            sm.reportShortcutUsed(id);
        } catch (RuntimeException ignored) {
        }
    }
}
