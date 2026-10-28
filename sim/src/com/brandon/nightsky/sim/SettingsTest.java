package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.SettingsFile;
import com.brandon.nightsky.processing.Site;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Presets, saved sites and the settings file (E6): the presets' and sites' JSON round trips, finding the site a
 * fix is at (an approximate fix one grid square over included), what a site learns, the settings file written
 * and read back with every type kept, junk in a hand-edited file left out, and settings from version 1.1 brought
 * up to this version without losing any.
 * <p>
 * Usage: SettingsTest
 */
public final class SettingsTest {
    static int failures;

    public static void main(String[] args) {
        presets();
        sites();
        startAtSite();
        file();
        migration();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static void presets() {
        List<Preset> b = Preset.builtIns();
        check(b.size() == 5 && b.get(0).id.equals(Preset.BACKYARD) && b.get(3).id.equals(Preset.QUICK) && b.get(4).id.equals(Preset.METEORS),
                "five built-in presets, in order");
        for (Preset p : b) System.out.println("  " + p.name + ": " + p.describe());
        Preset q = Preset.find(b, Preset.QUICK);
        check(q.durationMin == 2 && !q.saveDng && q.quick, "Quick look: 2 minutes, JPEG only, quick developing");
        Preset me = Preset.find(b, Preset.METEORS);
        check(me.durationMin == 30 && me.keepMeteors && !me.keepSatellites && !me.quick && me.describe().endsWith(", meteors kept"),
                "Meteors: 30 minutes, the meteors kept and the satellites taken out");
        Preset c = Preset.find(b, Preset.CLOUDY);
        check(c.collectClear && c.blend, "Partly cloudy: collects clear minutes and saves the cloud blur");
        for (int i = 0; i < b.size(); i++) {
            for (int k = 0; k < b.size(); k++) check(b.get(i).sameSettings(b.get(k)) == (i == k), "presets differ: " + i + " " + k);
        }
        Preset mine = c.copy();
        mine.id = "u1700000000000";
        mine.name = "Cabin \"north\" field";
        mine.builtIn = false;
        mine.durationMin = 20;
        mine.brightness = 0.37f;
        mine.rejection = "GESD";
        mine.trails = true;
        List<Preset> l = new ArrayList<Preset>();
        l.add(mine);
        String js = Preset.toJson(l);
        List<Preset> back = Preset.listFromJson(js);
        check(back.size() == 1 && back.get(0).sameSettings(mine) && back.get(0).name.equals(mine.name) && back.get(0).id.equals(mine.id),
                "preset JSON round trip: " + js);
        check(Preset.listFromJson("not json").isEmpty() && Preset.listFromJson("{\"a\":1}").isEmpty(), "junk gives no presets");
        // keeping the meteors (1.5)
        Preset met = Preset.find(b, Preset.BACKYARD).copy();
        met.keepMeteors = true;
        List<Preset> ml = new ArrayList<Preset>();
        ml.add(met);
        check(!met.sameSettings(Preset.find(b, Preset.BACKYARD)) && met.describe().endsWith("meteors kept")
                && Preset.listFromJson(Preset.toJson(ml)).get(0).keepMeteors, "a preset that keeps the meteors: " + met.describe());
        Preset all = met.copy(), all2 = met.copy();
        all.keepSatellites = all2.keepSatellites = true;
        all.keepMeteors = false;
        check(all.sameSettings(all2) && all.describe().endsWith("satellites and meteors kept"), "keeping them all keeps the meteors whatever "
                + "the meteors' own setting says");
        check("b".equals(SettingsFile.typeOf("keepMeteors")), "the settings file carries it");
    }

    static void sites() {
        // one degree of latitude is 111.2 km
        check(Math.abs(Site.distanceKm(40, -90, 41, -90) - 111.2) < 0.2, "distance: a degree of latitude");
        check(Math.abs(Site.distanceKm(0, 0, 0, 180) - Math.PI * 6371) < 1, "distance: half way round");
        Site home = site("s1", "Backyard", 41.8781, -87.6298, Preset.BACKYARD);
        Site cabin = site("s2", "Cabin", 45.95, -89.70, Preset.DARK);
        List<Site> all = new ArrayList<Site>();
        all.add(home);
        all.add(cabin);
        check(Site.nearest(all, 41.8800, -87.6300, Site.NEAR_KM) == home, "a fix 0.2 km away is at the Backyard");
        check(Site.nearest(all, 41.90, -87.6298, Site.NEAR_KM) == null, "2.4 km away isn't, for a precise fix");
        // an approximate fix one grid square over, diagonally (2.8 km)
        double lat = 41.8781 + 2.0 / 111.2, lon = -87.6298 + 2.0 / (111.2 * Math.cos(Math.toRadians(41.88)));
        double d = home.distanceKm(lat, lon);
        check(d > 2.7 && d < 2.9 && Site.nearKm(2000) >= 2.9, String.format("an approximate fix one square over (%.2f km) is at the site (within %.1f km)",
                d, Site.nearKm(2000)));
        check(Site.nearest(all, lat, lon, Site.nearKm(2000)) == home, "found with the approximate fix's reach");
        check(Site.nearKm(Double.NaN) == Site.NEAR_KM && Site.nearKm(50) == Site.NEAR_KM && Site.nearKm(100_000) == 4.0, "reach: 2 km, at most 4");
        check(Site.nearest(all, Double.NaN, 0, 5) == null, "no place, no site");
        check(Site.nearest(all, 45.9501, -89.7001, Site.NEAR_KM) == cabin, "the nearest site wins");

        // clear nights: the second best of three or more
        check(Double.isNaN(home.clearShare()), "no clear night yet");
        home.learnClear(0.40);
        check(Math.abs(home.clearShare() - 0.40) < 1e-9, "one clear night");
        home.learnClear(0.55);
        home.learnClear(0.48);
        check(Math.abs(home.clearShare() - 0.48) < 1e-9, "three: the second best (" + home.clearShare() + ")");
        home.learnClear(Double.NaN);
        home.learnClear(0);
        home.learnClear(3);
        check(home.clearShares.size() == 3, "nonsense shares ignored");
        for (int i = 0; i < 20; i++) home.learnClear(0.5);
        check(home.clearShares.size() == 10, "ten kept");

        // the sky's brightness
        check(home.skyText().isEmpty(), "no sky yet");
        home.addSky(1_700_000_000_000L, 19.2, true, "reading");
        home.addSky(1_700_100_000_000L, 18.1, false, "session");
        home.addSky(1_700_200_000_000L, 19.6, true, "session");
        home.addSky(1_700_300_000_000L, 5, true, "reading");
        check(home.sky.size() == 3 && home.validSkyCount() == 2 && Math.abs(home.skyMedian() - 19.4) < 1e-9,
                "sky median of the valid ones: " + home.skyText());
        home.noteObstruction(1_700_200_000_000L, "Something blocks the sky to the north-east (12% of the view)", 0.12, 45);
        home.sessions = 3;
        List<String> lines = home.describe(1_700_200_000_000L + 3 * 86_400_000L);
        for (String s : lines) System.out.println("  " + s);
        check(lines.size() == 4 && lines.get(2).endsWith("(3 days ago)"), "the sites page's lines");

        // round trip
        List<Site> back = Site.listFromJson(Site.toJson(all));
        check(back.size() == 2, "two sites back");
        Site h = back.get(0);
        check(h.id.equals("s1") && h.name.equals("Backyard") && Math.abs(h.latDeg - 41.8781) < 1e-7 && Math.abs(h.lonDeg + 87.6298) < 1e-7
                && h.presetId.equals(Preset.BACKYARD) && h.sessions == 3, "site: name, place, preset, sessions");
        check(Math.abs(h.clearShare() - home.clearShare()) < 1e-9 && h.clearShares.size() == 10, "site: clear nights");
        check(h.sky.size() == 3 && Math.abs(h.skyMedian() - 19.4) < 1e-6 && h.sky.get(1).source.equals("session") && !h.sky.get(1).valid,
                "site: sky history");
        check(h.obstruction.equals(home.obstruction) && Math.abs(h.obstructionFraction - 0.12) < 1e-9
                && Math.abs(h.obstructionAzimuthDeg - 45) < 1e-9 && h.obstructionMs == home.obstructionMs, "site: obstruction");
        check(Site.listFromJson("[{\"id\":\"x\",\"name\":\"no place\"}]").isEmpty(), "a site without a place is dropped");
    }

    /** A session starting near a saved site: whether its preset is picked, and what's said. */
    static void startAtSite() {
        List<Preset> presets = Preset.builtIns();
        Preset backyard = Preset.find(presets, Preset.BACKYARD), dark = Preset.find(presets, Preset.DARK);
        Site home = site("s1", "Backyard", 41.8781, -87.6298, Preset.BACKYARD);
        Site cabin = site("s2", "Cabin", 45.95, -89.70, Preset.DARK);
        List<Site> all = new ArrayList<Site>();
        all.add(home);
        all.add(cabin);
        // started 0.4 km from home, on settings no preset has, nothing changed by hand for a day
        Site at = Site.nearest(all, 41.8817, -87.6298, Site.nearKm(Double.NaN));
        Site.Pick k = at.decide(backyard, "", true, true, 86_400_000L);
        String said = Site.spoken(at.name, k.use ? k.preset.name : "");
        System.out.println("  at home: " + k.why + "; said \"" + said + "\"");
        check(at == home && k.use && k.preset == backyard && said.equals("Backyard site, Backyard preset."),
                "starting near the Backyard site picks its preset and says \"Backyard site, Backyard preset.\"");
        check(Site.spoken("Cabin", "Dark site").equals("Cabin site, Dark site preset."), "said at the cabin: " + Site.spoken("Cabin", "Dark site"));
        check(Site.spoken("Dark site", "My Preset").equals("Dark site, My Preset."), "a name that says site or preset already isn't doubled");
        check(Site.spoken("Cabin", "").equals("Cabin site."), "no preset: the site alone");
        // what keeps the settings as they are
        k = cabin.decide(dark, Preset.DARK, true, true, 86_400_000L);
        check(!k.use && k.why.contains("in place already"), "its preset is in place already: " + k.why);
        k = cabin.decide(dark, Preset.QUICK, true, true, 20 * 60_000L);
        check(!k.use && k.why.contains("20 minutes ago"), "a setting changed by hand 20 minutes ago stays: " + k.why);
        k = cabin.decide(dark, Preset.QUICK, true, true, 61 * 60_000L);
        check(k.use, "changed by hand over an hour ago: the site's preset");
        k = cabin.decide(dark, Preset.QUICK, true, false, 86_400_000L);
        check(!k.use && k.why.contains("asked for"), "a shortcut's or the night plan's settings stay: " + k.why);
        k = cabin.decide(dark, "", false, true, 86_400_000L);
        check(!k.use && k.why.contains("off"), "picking by itself turned off: " + k.why);
        k = cabin.decide(null, "", true, true, 86_400_000L);
        check(!k.use && k.why.contains("deleted"), "its preset was deleted: " + k.why);
        Site bare = site("s3", "Field", 40, -88, "");
        k = bare.decide(null, "", true, true, 86_400_000L);
        check(!k.use && k.why.contains("no preset"), "a site with no preset: " + k.why);
        // a clock set back makes "changed by hand" a time in the future: not a reason to keep them
        k = cabin.decide(dark, Preset.QUICK, true, true, -5 * 60_000L);
        check(k.use, "a hand change in the future (the clock went back) doesn't block it");
    }

    static Site site(String id, String name, double lat, double lon, String preset) {
        Site s = new Site();
        s.id = id;
        s.name = name;
        s.latDeg = lat;
        s.lonDeg = lon;
        s.presetId = preset;
        s.createdMs = 1_690_000_000_000L;
        return s;
    }

    /** The settings as a 1.2 phone stores them. */
    static Map<String, Object> phone() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("trails", false);
        m.put("durationMin", 10);
        m.put("brightness", 0.6f);
        m.put("sounds", true);
        m.put("saveDng", true);
        m.put("rejection", "AUTO");
        m.put("planMinBattery", 25);
        m.put("sqmOffset", -0.13f);
        m.put("preset", Preset.DARK);
        m.put("autoSite", true);
        m.put("blend", false);
        m.put("verifiedExp|0|google/mustang/mustang:16/BP4A", 32_000_000_000L);
        m.put("halo|0|google/mustang/mustang:16/BP4A", "0.11000,0.12500");
        m.put("drainPctPerHour", 9.5f);
        m.put("focusAssist", true);
        m.put("focusModel|0|Pixel 10 Pro XL", "d,0.15000,24.00,1761170000000,NaN,NaN,1.8000\nn,0.16000,12.00,1761170000000,1.4000,3.9000,NaN\n");
        m.put("lastResult", "content://media/external/images/media/1234");
        m.put("placeLat", 41.9f);
        m.put("askedLocation", true);
        m.put("settingsVersion", SettingsFile.VERSION);
        List<Preset> ps = new ArrayList<Preset>();
        Preset mine = Preset.find(Preset.builtIns(), Preset.CLOUDY).copy();
        mine.id = "u1";
        mine.name = "Mine";
        ps.add(mine);
        m.put(SettingsFile.PRESETS_KEY, Preset.toJson(ps));
        List<Site> ss = new ArrayList<Site>();
        ss.add(site("s1", "Backyard", 41.8781, -87.6298, Preset.BACKYARD));
        m.put(SettingsFile.SITES_KEY, Site.toJson(ss));
        m.put("somethingElse", "x");
        return m;
    }

    static void file() {
        Map<String, Object> m = phone();
        String text = SettingsFile.write(m, "1.2", 1_761_170_000_000L, TimeZone.getTimeZone("America/Chicago"));
        System.out.println("  file: " + text);
        check(!text.contains("lastResult") && !text.contains("placeLat") && !text.contains("askedLocation") && !text.contains("somethingElse"),
                "what the phone is doing isn't in the file");
        SettingsFile.Parsed p = SettingsFile.read("﻿" + text);
        check(p.version == SettingsFile.VERSION && p.appVersion.equals("1.2") && p.skipped.isEmpty() && p.notes.isEmpty(),
                "read back: version, nothing skipped " + p.skipped);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey();
            if (SettingsFile.typeOf(k) == null) {
                check(!p.values.containsKey(k), "left out: " + k);
                continue;
            }
            Object v = p.values.get(k);
            check(e.getValue().equals(v) && e.getValue().getClass() == v.getClass(), "kept, with its type: " + k + " = " + v);
        }
        check(p.settingCount() == 12, "12 settings (" + p.settingCount() + ")");
        check(p.presets.size() == 1 && p.presets.get(0).name.equals("Mine") && p.presets.get(0).collectClear, "the preset");
        check(p.sites.size() == 1 && p.sites.get(0).name.equals("Backyard") && p.sites.get(0).presetId.equals(Preset.BACKYARD), "the site");

        // a hand-edited file: wrong types, out of range, unknown names, a built-in preset
        String edited = "{\"app\":\"Night Sky\",\"file\":\"settings\",\"settingsVersion\":2,\"settings\":{\"trails\":\"yes\","
                + "\"durationMin\":7.5,\"brightness\":3,\"sounds\":false,\"countdownSec\":10,\"colour\":\"red\"},"
                + "\"learned\":{\"zp|cam\":\"1.0\",\"zp|\":\"2\",\"verifiedExp|cam\":\"long\"},"
                + "\"presets\":[{\"id\":\"quick\",\"name\":\"Quick look\"},{\"id\":\"u9\",\"name\":\"\"}],\"sites\":[{\"id\":\"q\"}]}";
        SettingsFile.Parsed e = SettingsFile.read(edited);
        System.out.println("  skipped: " + e.skipped);
        check(e.values.size() == 3 && Boolean.FALSE.equals(e.values.get("sounds")) && Integer.valueOf(10).equals(e.values.get("countdownSec"))
                && "1.0".equals(e.values.get("zp|cam")), "a hand-edited file: the good values kept " + e.values);
        check(e.skipped.size() == 9, "and nine bad ones left out, each said (" + e.skipped.size() + ")");
        check(e.presets.isEmpty() && e.sites.isEmpty(), "no bad presets or sites");
        for (String bad : new String[]{"", "[1,2]", "{\"app\":\"Other\"}", "{\"app\":\"Night Sky\",\"file\":\"plan\"}"}) {
            try {
                SettingsFile.read(bad);
                check(false, "not a settings file: " + bad);
            } catch (IllegalArgumentException x) {
                check(x.getMessage().contains("isn't a Night Sky settings file"), "refused: '" + bad + "': " + x.getMessage());
            }
        }
        // a newer app's file: what this one knows is read, the rest left out and said
        SettingsFile.Parsed n = SettingsFile.read("{\"app\":\"Night Sky\",\"file\":\"settings\",\"settingsVersion\":9,\"appVersion\":\"3.0\","
                + "\"settings\":{\"trails\":true,\"hologram\":true}}");
        check(n.values.size() == 1 && n.notes.size() == 1 && n.skipped.size() == 1, "a newer version's file: " + n.notes);
    }

    static void migration() {
        // version 1.1 had no version, no presets: its settings stay, and are the preset they match
        Map<String, Object> old = new HashMap<String, Object>();
        old.put("trails", false);
        old.put("durationMin", 3);
        old.put("brightness", 0.65f);
        old.put("sounds", false);
        old.put("rejection", "WINSORIZED");
        Map<String, Object> m = new HashMap<String, Object>(old);
        List<String> notes = SettingsFile.migrate(m, 1);
        check("".equals(m.get("preset")) && notes.size() == 1, "1.1 settings no preset has: kept as your own (" + notes + ")");
        for (Map.Entry<String, Object> e : old.entrySet()) check(e.getValue().equals(m.get(e.getKey())), "kept: " + e.getKey());
        check(m.size() == old.size() + 1, "nothing removed, one added");
        Map<String, Object> dark = new HashMap<String, Object>();
        dark.put("durationMin", 10);
        dark.put("brightness", 0.6f);
        SettingsFile.migrate(dark, 1);
        check(Preset.DARK.equals(dark.get("preset")), "1.1 settings that are the Dark site preset's: it's picked");
        Map<String, Object> again = new HashMap<String, Object>(m);
        check(SettingsFile.migrate(again, SettingsFile.VERSION).isEmpty() && again.equals(m), "migrating twice changes nothing");
        // a 1.1 file read in 1.2
        SettingsFile.Parsed p = SettingsFile.read("{\"app\":\"Night Sky\",\"file\":\"settings\",\"settings\":{\"durationMin\":2,"
                + "\"saveDng\":false,\"quick\":true}}");
        check(p.version == SettingsFile.VERSION && Preset.QUICK.equals(p.values.get("preset")), "an old file is migrated as it's read: " + p.notes);
    }

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
        if (!ok) failures++;
    }
}
