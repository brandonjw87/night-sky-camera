package com.brandon.intro;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything the intro reads from {@code assets/intro/}: the shared GLSL sources, the signed distance
 * fields of the name (half floats, rows bottom to top) and of the flat labels, and their layout numbers.
 * The same files drive the web preview, so both draw exactly the same frames.
 */
final class IntroAssets {
    static final String DIR = "intro/";
    static final String[] SHADERS = {"common.glsl", "fullscreen.vert", "scene.frag", "particles.vert",
            "particles.frag", "bright.frag", "down.frag", "up.frag", "streak.frag", "rays.frag", "final.frag"};

    /** One layout of the name: two lines for portrait, one line for landscape. */
    static final class NameField {
        int w, h;
        float emTex, spreadEm;
        float[] rect;   // object-space rectangle of the texture (em): x0, y0, w, h
        float[] ink;    // size of the inked area (em)
        ByteBuffer data;
    }

    final Map<String, String> shaders = new HashMap<String, String>();
    final NameField portrait = new NameField();
    final NameField landscape = new NameField();
    int labelsW, labelsH;
    float labelSpreadEm;
    float[] madeUv, madeSize, hudUv, hudSize;
    ByteBuffer labels;

    static IntroAssets load(AssetManager am) throws IOException {
        IntroAssets a = new IntroAssets();
        for (String s : SHADERS) a.shaders.put(s, new String(read(am, DIR + "shaders/" + s), "UTF-8"));
        try {
            JSONObject m = new JSONObject(new String(read(am, DIR + "sdf_manifest.json"), "UTF-8"));
            name(m.getJSONObject("portrait"), a.portrait);
            name(m.getJSONObject("landscape"), a.landscape);
            JSONObject l = m.getJSONObject("labels");
            a.labelsW = l.getInt("w");
            a.labelsH = l.getInt("h");
            a.labelSpreadEm = (float) l.getDouble("spreadEm");
            JSONObject items = l.getJSONObject("items");
            a.madeUv = floats(items.getJSONObject("madeBy").getJSONArray("uv"));
            a.madeSize = floats(items.getJSONObject("madeBy").getJSONArray("sizeEm"));
            a.hudUv = floats(items.getJSONObject("hud").getJSONArray("uv"));
            a.hudSize = floats(items.getJSONObject("hud").getJSONArray("sizeEm"));
        } catch (JSONException e) {
            throw new IOException("sdf_manifest.json: " + e.getMessage(), e);
        }
        a.portrait.data = direct(read(am, DIR + "name_portrait.r16f"), a.portrait.w * a.portrait.h * 2);
        a.landscape.data = direct(read(am, DIR + "name_landscape.r16f"), a.landscape.w * a.landscape.h * 2);
        a.labels = direct(read(am, DIR + "labels.r8"), a.labelsW * a.labelsH);
        return a;
    }

    private static void name(JSONObject o, NameField f) throws JSONException {
        f.w = o.getInt("w");
        f.h = o.getInt("h");
        f.emTex = (float) o.getDouble("emTex");
        f.spreadEm = (float) o.getDouble("spreadEm");
        f.rect = floats(o.getJSONArray("rect"));
        f.ink = floats(o.getJSONArray("ink"));
    }

    private static float[] floats(JSONArray a) throws JSONException {
        float[] r = new float[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = (float) a.getDouble(i);
        return r;
    }

    private static ByteBuffer direct(byte[] b, int expected) throws IOException {
        if (b.length != expected) throw new IOException("asset size " + b.length + ", expected " + expected);
        ByteBuffer buf = ByteBuffer.allocateDirect(b.length).order(ByteOrder.nativeOrder());
        buf.put(b).position(0);
        return buf;
    }

    static byte[] read(AssetManager am, String path) throws IOException {
        InputStream in = am.open(path, AssetManager.ACCESS_STREAMING);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 4096));
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
