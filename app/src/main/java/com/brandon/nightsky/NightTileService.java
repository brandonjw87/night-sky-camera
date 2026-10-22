package com.brandon.nightsky;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The "Night sky" Quick Settings tile (E3). A tap opens the app's start screen, over the lock screen if the phone
 * is locked (the activity shows when locked and turns the screen on), so a session is two taps away: the tile,
 * then Start. If Brandon asks for it in the app's settings, the tile asks to unlock the phone first instead.
 * Under its name it says what's happening: a session running, the plan armed and its next start, or tonight's
 * score and best window, worked out each time the panel opens.
 */
public final class NightTileService extends TileService {
    @Override
    public void onStartListening() {
        Glance.later(this, new Glance.Callback() {
            @Override
            public void done(Glance g) {
                show(g);
            }
        });
    }

    @Override
    public void onClick() {
        if (new Prefs(this).tileUnlocks() && isLocked()) {
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    open(false);
                }
            });
            return;
        }
        open(isLocked());
    }

    @SuppressWarnings("deprecation")
    private void open(boolean overLock) {
        Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCH)
                .setData(MainActivity.launchUri("home"))
                .putExtra(MainActivity.EXTRA_OVER_LOCK, overLock)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            // the Intent form throws for apps targeting 34 and later
            startActivityAndCollapse(PendingIntent.getActivity(this, 40, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void show(Glance g) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Night sky");
        t.setSubtitle(g.tile.isEmpty() ? null : g.tile);
        t.setState(g.active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setContentDescription("Night sky. " + g.tile);
        t.updateTile();
    }
}
