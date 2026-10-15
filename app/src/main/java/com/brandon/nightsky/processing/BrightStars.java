package com.brandon.nightsky.processing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stars to about magnitude 6.5 (Yale Bright Star Catalogue, the app's {@code stars.csv} asset), brightest
 * first: J2000 directions, V magnitudes, colour temperatures and names. Used to solve the frames' pointing
 * and to say what the photo shows.
 */
public final class BrightStars {
    public final int n;
    /** J2000 unit vectors, 3 per star. */
    public final double[] dir;
    public final float[] vmag;
    public final int[] kelvin;
    public final String[] con, letter, name;

    private BrightStars(int n) {
        this.n = n;
        dir = new double[3 * n];
        vmag = new float[n];
        kelvin = new int[n];
        con = new String[n];
        letter = new String[n];
        name = new String[n];
    }

    public static BrightStars load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        List<String[]> rows = new ArrayList<String[]>();
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split(",", -1);
                if (p.length >= 7) rows.add(p);
            }
        } finally {
            r.close();
        }
        BrightStars s = new BrightStars(rows.size());
        for (int i = 0; i < s.n; i++) {
            String[] p = rows.get(i);
            try {
                double[] v = Astro.vec(Double.parseDouble(p[0]) * Astro.DEG, Double.parseDouble(p[1]) * Astro.DEG);
                System.arraycopy(v, 0, s.dir, 3 * i, 3);
                s.vmag[i] = Float.parseFloat(p[2]);
                s.kelvin[i] = p[3].isEmpty() ? 0 : Integer.parseInt(p[3]);
            } catch (NumberFormatException e) {
                s.vmag[i] = 99;
            }
            s.con[i] = p[4];
            s.letter[i] = p[5];
            s.name[i] = p[6];
        }
        return s;
    }

    public double[] vec(int i) {
        return new double[]{dir[3 * i], dir[3 * i + 1], dir[3 * i + 2]};
    }

    /** Stars brighter than maxMag within radius (radians) of a J2000 direction, brightest first. */
    public int[] within(double[] center, double radius, double maxMag) {
        double c = Math.cos(radius);
        double nn = Math.sqrt(center[0] * center[0] + center[1] * center[1] + center[2] * center[2]);
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (vmag[i] > maxMag) continue;
            double d = (dir[3 * i] * center[0] + dir[3 * i + 1] * center[1] + dir[3 * i + 2] * center[2]) / nn;
            if (d >= c) out[k++] = i;
        }
        return java.util.Arrays.copyOf(out, k);
    }

    /** "Vega", "alpha Lyrae" style, or "" if the star has neither a name nor a letter. */
    public String label(int i) {
        if (!name[i].isEmpty()) return name[i];
        if (!letter[i].isEmpty() && !con[i].isEmpty()) return letter[i] + " " + genitive(con[i]);
        return "";
    }

    /** Full constellation name of an IAU abbreviation, or the abbreviation itself. */
    public static String constellation(String abbr) {
        String[] e = find(abbr);
        return e != null ? e[1] : abbr;
    }

    static String genitive(String abbr) {
        String[] e = find(abbr);
        return e != null ? e[2] : abbr;
    }

    private static String[] find(String abbr) {
        for (String[] e : CONSTELLATIONS) if (e[0].equalsIgnoreCase(abbr)) return e;
        return null;
    }

    /** Short description of a magnitude for spoken text. */
    static String brightness(float v) {
        return String.format(Locale.US, "magnitude %.1f", v);
    }

    // IAU abbreviation, name, genitive
    private static final String[][] CONSTELLATIONS = {
            {"And", "Andromeda", "Andromedae"}, {"Ant", "Antlia", "Antliae"}, {"Aps", "Apus", "Apodis"},
            {"Aqr", "Aquarius", "Aquarii"}, {"Aql", "Aquila", "Aquilae"}, {"Ara", "Ara", "Arae"},
            {"Ari", "Aries", "Arietis"}, {"Aur", "Auriga", "Aurigae"}, {"Boo", "Boötes", "Boötis"},
            {"Cae", "Caelum", "Caeli"}, {"Cam", "Camelopardalis", "Camelopardalis"}, {"Cnc", "Cancer", "Cancri"},
            {"CVn", "Canes Venatici", "Canum Venaticorum"}, {"CMa", "Canis Major", "Canis Majoris"},
            {"CMi", "Canis Minor", "Canis Minoris"}, {"Cap", "Capricornus", "Capricorni"}, {"Car", "Carina", "Carinae"},
            {"Cas", "Cassiopeia", "Cassiopeiae"}, {"Cen", "Centaurus", "Centauri"}, {"Cep", "Cepheus", "Cephei"},
            {"Cet", "Cetus", "Ceti"}, {"Cha", "Chamaeleon", "Chamaeleontis"}, {"Cir", "Circinus", "Circini"},
            {"Col", "Columba", "Columbae"}, {"Com", "Coma Berenices", "Comae Berenices"},
            {"CrA", "Corona Australis", "Coronae Australis"}, {"CrB", "Corona Borealis", "Coronae Borealis"},
            {"Crv", "Corvus", "Corvi"}, {"Crt", "Crater", "Crateris"}, {"Cru", "Crux", "Crucis"},
            {"Cyg", "Cygnus", "Cygni"}, {"Del", "Delphinus", "Delphini"}, {"Dor", "Dorado", "Doradus"},
            {"Dra", "Draco", "Draconis"}, {"Equ", "Equuleus", "Equulei"}, {"Eri", "Eridanus", "Eridani"},
            {"For", "Fornax", "Fornacis"}, {"Gem", "Gemini", "Geminorum"}, {"Gru", "Grus", "Gruis"},
            {"Her", "Hercules", "Herculis"}, {"Hor", "Horologium", "Horologii"}, {"Hya", "Hydra", "Hydrae"},
            {"Hyi", "Hydrus", "Hydri"}, {"Ind", "Indus", "Indi"}, {"Lac", "Lacerta", "Lacertae"}, {"Leo", "Leo", "Leonis"},
            {"LMi", "Leo Minor", "Leonis Minoris"}, {"Lep", "Lepus", "Leporis"}, {"Lib", "Libra", "Librae"},
            {"Lup", "Lupus", "Lupi"}, {"Lyn", "Lynx", "Lyncis"}, {"Lyr", "Lyra", "Lyrae"}, {"Men", "Mensa", "Mensae"},
            {"Mic", "Microscopium", "Microscopii"}, {"Mon", "Monoceros", "Monocerotis"}, {"Mus", "Musca", "Muscae"},
            {"Nor", "Norma", "Normae"}, {"Oct", "Octans", "Octantis"}, {"Oph", "Ophiuchus", "Ophiuchi"},
            {"Ori", "Orion", "Orionis"}, {"Pav", "Pavo", "Pavonis"}, {"Peg", "Pegasus", "Pegasi"},
            {"Per", "Perseus", "Persei"}, {"Phe", "Phoenix", "Phoenicis"}, {"Pic", "Pictor", "Pictoris"},
            {"Psc", "Pisces", "Piscium"}, {"PsA", "Piscis Austrinus", "Piscis Austrini"}, {"Pup", "Puppis", "Puppis"},
            {"Pyx", "Pyxis", "Pyxidis"}, {"Ret", "Reticulum", "Reticuli"}, {"Sge", "Sagitta", "Sagittae"},
            {"Sgr", "Sagittarius", "Sagittarii"}, {"Sco", "Scorpius", "Scorpii"}, {"Scl", "Sculptor", "Sculptoris"},
            {"Sct", "Scutum", "Scuti"}, {"Ser", "Serpens", "Serpentis"}, {"Sex", "Sextans", "Sextantis"},
            {"Tau", "Taurus", "Tauri"}, {"Tel", "Telescopium", "Telescopii"}, {"Tri", "Triangulum", "Trianguli"},
            {"TrA", "Triangulum Australe", "Trianguli Australis"}, {"Tuc", "Tucana", "Tucanae"},
            {"UMa", "Ursa Major", "Ursae Majoris"}, {"UMi", "Ursa Minor", "Ursae Minoris"}, {"Vel", "Vela", "Velorum"},
            {"Vir", "Virgo", "Virginis"}, {"Vol", "Volans", "Volantis"}, {"Vul", "Vulpecula", "Vulpeculae"},
    };
}
