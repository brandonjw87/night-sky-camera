package com.brandon.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.SettingsFile;
import com.brandon.nightsky.processing.Site;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The settings file on the phone (E6): every setting, preset and site saved as one JSON file in Documents/NightSky,
 * and read back from a file Brandon picks. Loading one first saves the settings as they are, so it can be undone.
 * Nothing leaves the phone: the file is Brandon's to copy elsewhere.
 */
final class SettingsIO {
    /** Bigger than any real settings file (a site keeps at most 100 sky measurements). */
    static final int MAX_BYTES = 4 << 20;

    private SettingsIO() {}

    /** "NightSky-settings_2026-10-22_2130.json" */
    static String fileName(String what, long nowMs) {
        return "NightSky-" + what + "_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(nowMs)) + ".json";
    }

    /** Saves everything as a file in Documents/NightSky ({@code name}: see {@link #fileName}); its address. */
    static Uri export(Context c, String name) throws IOException {
        Prefs prefs = new Prefs(c);
        final String text = SettingsFile.write(prefs.all(), BuildInfo.VERSION, System.currentTimeMillis(), TimeZone.getDefault());
        return MediaSaver.saveDocument(c, name, "application/json", new MediaSaver.Writer() {
            @Override
            public void write(OutputStream os) throws IOException {
                os.write(text.getBytes("UTF-8"));
            }
        });
    }

    /** Reads a settings file; IllegalArgumentException (words for the screen) if it isn't one. */
    static SettingsFile.Parsed read(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("The file couldn't be opened");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) throw new IllegalArgumentException("That file is too big to be a Night Sky settings file.");
            }
        } finally {
            in.close();
        }
        return SettingsFile.read(new String(bo.toByteArray(), "UTF-8"));
    }

    /**
     * Puts a read file's settings in place: its settings over these, its presets and sites added (one with the same
     * name as one here replaces it), after saving the settings as they are. Returns what it did, for the screen.
     */
    static String apply(Context c, SettingsFile.Parsed p) throws IOException {
        String before = fileName("settings-before-loading", System.currentTimeMillis());
        export(c, before);
        Prefs prefs = new Prefs(c);
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Object> x : p.values.entrySet()) Prefs.put(e, x.getKey(), x.getValue());
        e.putInt(SettingsFile.VERSION_KEY, SettingsFile.VERSION);
        // a setting loaded is a setting picked: a saved site's preset doesn't replace it straight away
        e.putLong("handPickedMs", System.currentTimeMillis());
        e.commit();
        List<Preset> own = Presets.own(prefs);
        for (Preset np : p.presets) {
            Preset same = null;
            for (Preset op : own) if (op.id.equals(np.id) || op.name.equalsIgnoreCase(np.name)) same = op;
            if (same != null) own.remove(same);
            own.add(np);
        }
        prefs.setUserPresetsJson(Preset.toJson(own));
        final List<Site> in = p.sites;
        Sites.change(prefs, new Sites.Change() {
            @Override
            public void apply(List<Site> sites) {
                for (Site ns : in) {
                    List<Site> same = new ArrayList<Site>();
                    for (Site os : sites) if (os.id.equals(ns.id) || os.name.equalsIgnoreCase(ns.name)) same.add(os);
                    sites.removeAll(same);
                    sites.add(ns);
                }
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Loaded %d settings, %d %s and %d %s.", p.settingCount(), p.presets.size(),
                p.presets.size() == 1 ? "preset" : "presets", p.sites.size(), p.sites.size() == 1 ? "site" : "sites"));
        if (!p.skipped.isEmpty()) sb.append(" Left out: ").append(android.text.TextUtils.join(", ", p.skipped)).append('.');
        if (!p.notes.isEmpty()) sb.append(" Brought up to date: ").append(android.text.TextUtils.join("; ", p.notes)).append('.');
        sb.append(" The settings as they were are saved in Documents/NightSky as ").append(before).append('.');
        return sb.toString();
    }
}
