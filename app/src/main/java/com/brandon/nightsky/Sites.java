package com.brandon.nightsky;

import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.Site;
import com.brandon.nightsky.processing.SkyReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saved sites on the phone (E6), kept in the settings as JSON. The screen and the sessions both change them (a
 * session adds what it measured there), so every change reads, changes and writes under one lock.
 */
final class Sites {
    private static final Object LOCK = new Object();

    private Sites() {}

    interface Change {
        void apply(List<Site> sites);
    }

    static List<Site> load(Prefs prefs) {
        synchronized (LOCK) {
            return Site.listFromJson(prefs.sitesJson());
        }
    }

    static void change(Prefs prefs, Change c) {
        synchronized (LOCK) {
            List<Site> l = Site.listFromJson(prefs.sitesJson());
            c.apply(l);
            prefs.setSitesJson(Site.toJson(l));
        }
    }

    /** Changes one site (nothing if it's gone). */
    static void changeOne(Prefs prefs, final String id, final Change c) {
        if (id == null || id.isEmpty()) return;
        change(prefs, new Change() {
            @Override
            public void apply(List<Site> sites) {
                Site s = Site.find(sites, id);
                if (s == null) return;
                List<Site> one = new ArrayList<Site>();
                one.add(s);
                c.apply(one);
            }
        });
    }

    /** The saved site a place is at (null if none, or no place). */
    static Site at(List<Site> sites, Locator.Place p) {
        if (p == null) return null;
        return Site.nearest(sites, p.latDeg, p.lonDeg, Site.nearKm(p.accuracyM));
    }

    static Site add(Prefs prefs, String name, Locator.Place p, String presetId) {
        final Site s = new Site();
        s.id = "s" + System.currentTimeMillis();
        s.name = name.trim();
        s.latDeg = p.latDeg;
        s.lonDeg = p.lonDeg;
        s.presetId = presetId == null ? "" : presetId;
        s.createdMs = System.currentTimeMillis();
        change(prefs, new Change() {
            @Override
            public void apply(List<Site> l) {
                l.add(s);
            }
        });
        return s;
    }

    static void delete(Prefs prefs, final String id) {
        change(prefs, new Change() {
            @Override
            public void apply(List<Site> l) {
                Site s = Site.find(l, id);
                if (s != null) l.remove(s);
            }
        });
    }

    /** At {@code site}: whether its preset takes the place of the settings in use (see {@link Site#decide}). */
    static Site.Pick decide(Prefs prefs, Site site, String currentId, boolean usesSaved, long nowMs) {
        Preset p = site.presetId.isEmpty() ? null : Presets.find(prefs, site.presetId);
        return site.decide(p, currentId, prefs.autoSite(), usesSaved, nowMs - prefs.handPickedMs());
    }

    // ---- what a session learns at a site ----

    /**
     * The pre-flight check's findings: what blocks the sky, and the sky's brightness (counted for the site's
     * darkness only when it's dark, clear and the Moon is down, as for a sky reading).
     */
    static void learnCheck(Prefs prefs, String id, final Preflight.Report rep, final double offset, final long nowMs) {
        changeOne(prefs, id, new Change() {
            @Override
            public void apply(List<Site> one) {
                Site s = one.get(0);
                Preflight.Item ob = rep.item("obstruction");
                if (ob != null) {
                    int pct = (int) Math.max(1, Math.round(100 * rep.obstructedFraction));
                    String text = !Double.isNaN(rep.obstructionAzimuthDeg) && !rep.obstructionSide.isEmpty()
                            ? String.format(Locale.US, "Something blocks the sky to the %s (%d%% of the view).", rep.obstructionSide, pct)
                            : String.format(Locale.US, "Something blocked %d%% of the view (which way wasn't known).", pct);
                    s.noteObstruction(nowMs, text, rep.obstructedFraction, rep.obstructionAzimuthDeg);
                } else if (rep.solution != null || rep.stars >= 30) {
                    // the frame was read well enough to say nothing was in the way
                    s.noteObstruction(nowMs, "", 0, Double.NaN);
                }
                if (SkyReading.measured(rep)) {
                    List<Preflight.Report> l = new ArrayList<Preflight.Report>();
                    l.add(rep);
                    SkyReading r = SkyReading.of(l, nowMs, offset, "");
                    s.addSky(nowMs, r.meterMag, r.valid, "session");
                }
                s.lastUsedMs = nowMs;
            }
        });
    }

    /** A sky reading taken here. */
    static void learnReading(Prefs prefs, String id, final SkyReading r) {
        if (r.frames == 0) return;
        changeOne(prefs, id, new Change() {
            @Override
            public void apply(List<Site> one) {
                one.get(0).addSky(r.timeMs, r.meterMag, r.valid, "reading");
                one.get(0).lastUsedMs = r.timeMs;
            }
        });
    }

    /** A session here went well: the share of the stars its check saw is what a clear night shows here. */
    static void learnClear(Prefs prefs, String id, final double share) {
        changeOne(prefs, id, new Change() {
            @Override
            public void apply(List<Site> one) {
                one.get(0).learnClear(share);
            }
        });
    }

    /** A session started capturing here. */
    static void noteSession(Prefs prefs, String id, final long nowMs) {
        changeOne(prefs, id, new Change() {
            @Override
            public void apply(List<Site> one) {
                one.get(0).sessions++;
                one.get(0).lastUsedMs = nowMs;
            }
        });
    }
}
