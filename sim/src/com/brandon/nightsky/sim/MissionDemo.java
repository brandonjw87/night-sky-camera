package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.MissionControl;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.PreviewAccumulator;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;
import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.RemoteServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Mission Control on a desktop (2.3): a whole pretend night through the app's own engine (the satellite session of
 * {@link MissionTest}: real orbits, a Starlink train, the ISS, a plane, a meteor), in real time or faster, served by the
 * phone's own web server with the real pages, so Mission Control can be tried (and screenshotted) in a browser.
 *
 * <pre>
 * java -cp build/sim com.brandon.nightsky.sim.MissionDemo app/src/main/res/raw [port] [speed] [scale] [frames]
 * </pre>
 * It prints the address with the token. The pages are read from the folder at each request, so they can be edited
 * while it runs. {@code speed} > 1 runs the night faster (the satellites then glide faster too).
 */
public final class MissionDemo implements RemoteServer.Backend, RemoteServer.Mission {
    /** JPEGs by the desktop's own encoder. */
    static final class ImageIoJpeg implements MissionControl.Jpeg {
        @Override
        public byte[] encode(int[] argb, int w, int h, int quality) {
            try {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, w, h, argb, 0, w);
                return jpeg(img, quality);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    static byte[] jpeg(BufferedImage img, int quality) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter wr = it.next();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageOutputStream ios = ImageIO.createImageOutputStream(bo);
        wr.setOutput(ios);
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality / 100f);
        wr.write(null, new IIOImage(img, null, null), p);
        ios.close();
        wr.dispose();
        return bo.toByteArray();
    }

    private final File pages;
    private final double speed;
    private final int scale, frames;
    private final MissionControl mc;
    private final MissionTest.FakeStatus st = new MissionTest.FakeStatus();
    private volatile long simNowMs;
    private final long startMs;
    private volatile byte[] stackJpeg, latestJpeg;
    private volatile int previewVersion = -1, done;
    private volatile String headline = "Getting ready", detail = "";
    private final SkySim sim;
    private final MissionTest.Scenario sc;
    private final BrightStars catalog;

    MissionDemo(File pages, double speed, int scale, int frames) throws Exception {
        this.pages = pages;
        this.speed = speed;
        this.scale = scale;
        this.frames = frames;
        catalog = SimRunner.loadCatalog();
        startMs = SatSessionTest.darkStart();
        sc = MissionTest.scenario(scale, frames, 1, startMs, catalog);
        sim = sc.sim;
        simNowMs = startMs - 40_000;
        mc = new MissionControl("", "", new ImageIoJpeg(), null);
        mc.setClock(new MissionControl.Clock() {
            @Override
            public long now() {
                return simNowMs;
            }
        });
        mc.setStatus(st);
        mc.setCatalogue(catalog);
        mc.setLogger(new MissionControl.Logger() {
            @Override
            public void log(String line) {
                System.out.println(line);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        File pages = new File(args.length > 0 ? args[0] : "app/src/main/res/raw");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        double speed = args.length > 2 ? Double.parseDouble(args[2]) : 1;
        int scale = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        int frames = args.length > 4 ? Integer.parseInt(args[4]) : 37;
        MissionDemo demo = new MissionDemo(pages, speed, scale, frames);
        RemoteServer server = new RemoteServer(demo);
        int p = server.start(port);
        System.out.println("mission: http://127.0.0.1:" + p + "/mission?t=" + server.token());
        System.out.println("viewer: http://127.0.0.1:" + p + "/?t=" + server.token());
        System.out.println("pairing code: " + server.pairCode());
        System.out.flush();
        demo.night();
        System.out.println("night over; still serving");
        while (true) Thread.sleep(1000);
    }

    /** Waits {@code simS} seconds of the night (real seconds / speed), the clock moving along. */
    private void pass(double simS) throws InterruptedException {
        long end = simNowMs + Math.round(simS * 1000);
        while (simNowMs < end) {
            Thread.sleep(50);
            simNowMs = Math.min(end, simNowMs + Math.round(50 * speed));
        }
    }

    private void night() throws Exception {
        Thread.sleep(Math.round(Double.parseDouble(System.getProperty("mission.wait", "3")) * 1000));
        final SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = Files.createTempDirectory("mission-demo").toFile();
        SessionStore store = SessionStore.create(root);
        final SessionSky sky = new SessionSky();
        sky.latDeg = SatSessionTest.LAT;
        sky.lonDeg = SatSessionTest.LON;
        sky.altM = 0;
        sky.accuracyM = 30;
        sky.locationSource = "simulation";
        sky.clockWallMs = startMs;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        mc.newSession(sky, catalog, sc.cat, sim.W, sim.H, frames, true);
        st.state = "arming";
        headline = "Lay the phone screen-down";
        pass(6);
        st.state = "focusing";
        headline = "Focusing";
        pass(6);
        st.state = "checking";
        headline = "Checking the sky";
        pass(6);
        st.state = "countdown";
        headline = "Starting in 10";
        pass(10);
        simNowMs = startMs;
        st.state = "capturing";
        headline = "Capturing";
        mc.captureStarted(simNowMs);
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
                mc.unit(rec, live, unitsDone);
                done = unitsDone;
                RgbImage img = src.preview().image();
                if (img == null) return;
                Renderer.Analysis a = Renderer.analyze(img, spec);
                st.analysis = a;
                Renderer.Params p = new Renderer.Params();
                int[] px = Renderer.renderSmall(img.binned(1), a, p, 1);
                int f = PreviewAccumulator.FACTOR;
                stackJpeg = new ImageIoJpeg().encode(px, img.cropWidth(), img.cropHeight(), 88);
                previewVersion++;
                mc.livePicture(img, new int[]{img.x0 * f, img.y0 * f, img.cropWidth() * f, img.cropHeight() * f}, previewVersion);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        up.setWatcher(mc.watcher(up));
        up.setKeepLatest(true);
        double total = sim.frameStart(frames - 1) + sim.frameExp;
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, 1);
            // the frame is exposed while the clock runs
            double until = sim.frameStart(i) + sim.frameExp;
            pass(until - (simNowMs - startMs) / 1000.0);
            st.elapsed = simNowMs - startMs;
            st.remaining = Math.max(0, Math.round(total * 1000) - st.elapsed);
            detail = String.format(Locale.US, "Frame %d of %d", i + 1, frames);
            up.submit(buf, info);
        }
        up.finish();
        st.state = "developing";
        headline = "Developing";
        detail = "Stacking the frames";
        PostProcessor pp = new PostProcessor(store, spec);
        pp.sky = sky;
        pp.satellites = sc.cat;
        PostProcessor.Output o = pp.run(false, new Progress() {
            @Override
            public void update(String stage, float fraction) {
                detail = stage;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        // the photo as the result screen shows it: the stack's small copy, rendered
        int factor = 2;
        RgbImage small = o.image.binned(factor);
        int[] px = Renderer.renderPreview(small, o.analysis, new Renderer.Params(), factor);
        byte[] photo = new ImageIoJpeg().encode(px, small.cropWidth(), small.cropHeight(), 90);
        MissionControl.Photo p = new MissionControl.Photo();
        p.jpeg = photo;
        p.width = small.cropWidth();
        p.height = small.cropHeight();
        p.sol = o.solution;
        p.jdUtc = o.solutionJd;
        p.crop = new int[]{o.analysis.cx0, o.analysis.cy0, small.cropWidth() * factor, small.cropHeight() * factor};
        p.small = small;
        p.streaks = o.streaks;
        p.meteors = o.meteors.size();
        p.startMs = startMs;
        p.endMs = simNowMs;
        p.seconds = total;
        p.fresh = true;
        mc.photo(p);
        stackJpeg = photo;
        previewVersion++;
        st.state = "done";
        headline = "Your photo is ready";
        detail = o.summary;
        store.deleteBulk();
    }

    // ------------------------------------------------------------------------------------------------

    private byte[] read(String name) {
        try {
            return Files.readAllBytes(new File(pages, name).toPath());
        } catch (IOException e) {
            return ("<!doctype html><p>" + name + " is missing").getBytes();
        }
    }

    @Override
    public byte[] page() {
        return read("viewer.html");
    }

    @Override
    public byte[] missionPage() {
        mc.opened();
        return read("mission.html");
    }

    @Override
    public String status() {
        Json j = new Json().obj();
        j.put("app", "Night Sky demo").put("state", st.state).put("phase", st.state.toUpperCase(Locale.US));
        j.put("headline", headline).put("detail", detail).put("mode", "deep");
        boolean capturing = "capturing".equals(st.state);
        j.put("progress", capturing ? done / (double) frames : -1);
        j.put("frame", Math.min(frames, done + (capturing ? 1 : 0))).put("frames", frames);
        j.put("elapsedMs", st.elapsed).put("remainingMs", st.remaining);
        j.put("exposureS", sim.frameExp).put("iso", 1600);
        j.put("previewVersion", stackJpeg != null ? previewVersion : -1).put("latestVersion", done - 1);
        j.put("muted", false).put("forced", false).put("nowMs", simNowMs);
        j.put("unitsCount", done).put("used", done).put("rejected", 0);
        j.obj("battery").put("level", 81).put("tempC", 23.5).put("charging", false).end();
        j.put("thermal", "normal").put("stopsInMin", 30);
        j.obj("can").put("stop", capturing).put("addTime", capturing).end();
        return j.end().toString();
    }

    @Override
    public String units(int from) {
        return "[]";
    }

    @Override
    public RemoteServer.Picture stack() {
        byte[] s = stackJpeg;
        return s == null ? null : new RemoteServer.Picture(s, previewVersion);
    }

    @Override
    public RemoteServer.Picture latest() {
        return stack();
    }

    @Override
    public String action(String name, Map<String, String> args) {
        if ("name-star".equals(name)) return mc.nameStar(args);
        if ("unname-star".equals(name)) return mc.unnameStar(args);
        return null;
    }

    @Override
    public List<RemoteServer.Download> downloads() {
        return new ArrayList<RemoteServer.Download>();
    }

    @Override
    public InputStream open(RemoteServer.Download d) {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String events(int since) {
        return mc.eventsJson(since);
    }

    @Override
    public String sky() {
        return mc.skyJson();
    }

    @Override
    public String sats() {
        return mc.satsJson();
    }

    @Override
    public String song() {
        return mc.songJson();
    }

    @Override
    public String patches() {
        return mc.patchesJson();
    }

    @Override
    public byte[] photo() {
        return mc.photoJpeg();
    }

    @Override
    public byte[] snap(int id) {
        return mc.snap(id);
    }
}
