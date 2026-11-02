package com.brandon.nightsky;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The photo's video off the phone (1.8): Robolectric has no video encoder, so the frames go to ffmpeg's H.264 encoder
 * and come out as a real MP4, as the phone's would (BT.709 colours, limited range, as the app asks of the phone's). Without ffmpeg, a stand-in of the right shape (an ftyp box, then
 * the frames' bytes), so the rest of the path still runs.
 */
final class FfmpegVideo implements VideoEncoder {
    static final VideoEncoder.Factory FACTORY = new VideoEncoder.Factory() {
        @Override
        public boolean supports(int w, int h) {
            return w >= 16 && h >= 16 && (w & 1) == 0 && (h & 1) == 0;
        }

        @Override
        public VideoEncoder open(File out, int w, int h, int fps) throws IOException {
            opened++;
            return new FfmpegVideo(out, w, h, fps);
        }
    };
    /** Encoders opened and videos finished, for the checks. */
    static volatile int opened, finished;
    /** Fail the next video after this many frames (-1: don't), to check the photo is saved without it. */
    static volatile int failAfter = -1;

    private final File out;
    private final int w, h;
    private Process ff;
    private OutputStream in;
    private int frames;
    private long lastPts = -1;
    private final boolean real;

    private FfmpegVideo(File out, int w, int h, int fps) throws IOException {
        this.out = out;
        this.w = w;
        this.h = h;
        real = haveFfmpeg();
        if (real) {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra",
                    "-s", w + "x" + h, "-r", Integer.toString(fps), "-i", "-", "-vf", "scale=out_color_matrix=bt709:out_range=tv",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-color_range", "tv",
                    "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-crf", "20", out.getAbsolutePath());
            pb.redirectErrorStream(true);
            // what it says goes with the test's output, not in the session's folder
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(Monkey.out != null ? Monkey.out : out.getParentFile(), "ffmpeg.log")));
            ff = pb.start();
            in = new BufferedOutputStream(ff.getOutputStream(), 1 << 20);
        } else {
            in = new BufferedOutputStream(new java.io.FileOutputStream(out), 1 << 20);
            in.write(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'a', 'v', 'c', '1'});
        }
    }

    @Override
    public void frame(int[] argb, long ptsUs) throws IOException {
        if (in == null) throw new IOException("closed");
        if (argb.length != w * h) throw new IOException("a frame of " + argb.length + " pixels for a " + w + "x" + h + " video");
        if (ptsUs <= lastPts) throw new IOException("frames out of order");
        if (failAfter >= 0 && frames >= failAfter) {
            failAfter = -1;
            throw new IOException("the test's encoder failed on purpose");
        }
        lastPts = ptsUs;
        byte[] b = new byte[w * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = argb[y * w + x];
                b[4 * x] = (byte) v;
                b[4 * x + 1] = (byte) (v >> 8);
                b[4 * x + 2] = (byte) (v >> 16);
                b[4 * x + 3] = (byte) 0xFF;
            }
            in.write(b);
        }
        frames++;
    }

    @Override
    public void finish() throws IOException {
        try {
            in.close();
            in = null;
            if (ff != null) {
                int rc = ff.waitFor();
                ff = null;
                if (rc != 0) throw new IOException("ffmpeg failed");
            }
            if (out.length() == 0) throw new IOException("ffmpeg made nothing");
            finished++;
        } catch (InterruptedException e) {
            throw new IOException(e);
        } finally {
            close();
        }
    }

    /**
     * Let go, unfinished: ffmpeg is stopped before its input is closed, and waited for, so it doesn't finish the file
     * after the app has deleted it (as the phone's encoder, released, writes nothing more).
     */
    @Override
    public void close() {
        if (ff != null) {
            ff.destroyForcibly();
            try {
                ff.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ff = null;
        }
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // the pipe is gone with ffmpeg
            }
            in = null;
        }
    }

    @Override
    public String note() {
        return String.format(Locale.US, "%s off the phone, %d frames, %.1f MB", real ? "ffmpeg" : "a stand-in", frames, out.length() / 1e6);
    }

    static boolean haveFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            InputStream s = p.getInputStream();
            byte[] b = new byte[4096];
            while (s.read(b) > 0) {
                // drained, so it can finish
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
