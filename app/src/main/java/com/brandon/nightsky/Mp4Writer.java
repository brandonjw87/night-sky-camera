package com.brandon.nightsky;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.brandon.nightsky.processing.Parallel;
import com.brandon.nightsky.processing.PhotoVideo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The photo's video (1.8) as an H.264 MP4: frames in as ARGB at their own presentation times, converted to YUV 4:2:0
 * (BT.709, limited range, as phone videos are) into the phone's video encoder, and out into an MP4 file. Any trouble
 * throws an IOException, and the photo is saved without its video.
 * <p>
 * The photo's sRGB codes go in as they are, though BT.709's transfer curve isn't sRGB's: the Pixel's own Motion Photos
 * do the same (a Pixel sample's video, tagged BT.709, has the codes its photo has, within a few levels in the shadows;
 * re-encoded to BT.709's curve, the night sky's codes would be half of them), and Google Photos shows those matched to
 * their photos. Limited range, not the Pixel's full range: a player that missed a full-range tag would crush the sky's
 * codes below 16 to black, and nothing here can check that the phone's encoder writes the tag.
 */
final class Mp4Writer implements VideoEncoder {
    /** Makes the encoders the photo's videos go into: the phone's own; the off-phone harness puts in its own. */
    static VideoEncoder.Factory factory = new VideoEncoder.Factory() {
        @Override
        public boolean supports(int w, int h) {
            return encoderFor(w, h, PhotoVideo.FPS, false) != null;
        }

        @Override
        public VideoEncoder open(File out, int w, int h, int fps) throws IOException {
            return new Mp4Writer(out, w, h, fps, bitRate(w, h, fps), null, 0);
        }

        @Override
        public VideoEncoder open(File out, int w, int h, int fps, int bitRate) throws IOException {
            return new Mp4Writer(out, w, h, fps, bitRate, null, 0);
        }

        @Override
        public VideoEncoder open(File out, int w, int h, int fps, int bitRate, short[] pcm, int rate) throws IOException {
            return new Mp4Writer(out, w, h, fps, bitRate, pcm, rate);
        }
    };

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long WAIT_US = 10_000;
    /** How long the encoder may take to hand over its last frames (ms). */
    private static final long END_MS = 15_000;

    private final int w, h;
    private final String codecName;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxing;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long lastPts = -1, bytes;
    private int frames;
    /**
     * The sound track (2.3), if any: encoded to AAC before the first picture, its format and its samples kept, each
     * written into the file just before the first picture at or after its time (the file's tracks interleaved).
     */
    private MediaFormat audioFormat;
    private final java.util.ArrayDeque<AudioSample> audio = new java.util.ArrayDeque<AudioSample>();
    private final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
    private int audioTrack = -1;
    private long audioBytes;

    private static final class AudioSample {
        byte[] data;
        long ptsUs;
        int flags;
    }

    private Mp4Writer(File out, int w, int h, int fps, int bitRate, short[] pcm, int rate) throws IOException {
        if ((w & 1) != 0 || (h & 1) != 0 || w < 16 || h < 16) throw new IOException("a " + w + "x" + h + " video can't be made");
        this.w = w;
        this.h = h;
        if (pcm != null && pcm.length >= 2) encodeAudio(pcm, rate);
        MediaFormat f = format(w, h, fps, true, bitRate);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
        boolean high = name != null;
        if (name == null) {
            f = format(w, h, fps, false, bitRate);
            name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
        }
        if (name == null) throw new IOException("the phone has no H.264 encoder for " + w + "x" + h);
        codecName = name;
        try {
            codec = MediaCodec.createByCodecName(name);
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException e) {
                if (!high) throw e;
                // the High profile was listed but not taken: the encoder's own choice
                codec.reset();
                codec.configure(format(w, h, fps, false, bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            codec.start();
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException | RuntimeException e) {
            close();
            throw new IOException("the video encoder (" + name + ") wouldn't start: " + e, e);
        }
    }

    /** Bits a second of the sound track. */
    private static final int AUDIO_BIT_RATE = 160_000;
    /** Longest the sound may take to encode (ms): 27 seconds of it takes well under one. */
    private static final long AUDIO_MS = 60_000;

    /** The whole sound track into AAC-LC samples (kept in {@link #audio}), and its format. */
    private void encodeAudio(short[] pcm, int rate) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
        if (name == null) throw new IOException("the phone has no AAC encoder for the sound");
        MediaCodec a = null;
        try {
            a = MediaCodec.createByCodecName(name);
            a.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            a.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int total = pcm.length / 2, pos = 0;
            boolean inEnd = false;
            long t0 = System.currentTimeMillis();
            while (true) {
                if (System.currentTimeMillis() - t0 > AUDIO_MS) throw new IOException("the sound encoder didn't finish");
                if (!inEnd) {
                    int ix = a.dequeueInputBuffer(WAIT_US);
                    if (ix >= 0) {
                        ByteBuffer in = a.getInputBuffer(ix);
                        if (in == null) throw new IOException("the sound encoder gave no buffer");
                        in.clear();
                        int n = Math.min(in.remaining() / 4, total - pos);
                        long pts = pos * 1_000_000L / rate;
                        if (n <= 0) {
                            a.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEnd = true;
                        } else {
                            // 16-bit PCM is in the phone's own byte order
                            in.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 2 * pos, 2 * n);
                            a.queueInputBuffer(ix, 0, 4 * n, pts, 0);
                            pos += n;
                        }
                    }
                }
                int ox = a.dequeueOutputBuffer(bi, WAIT_US);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioFormat = a.getOutputFormat();
                    continue;
                }
                if (ox < 0) continue;
                ByteBuffer out = a.getOutputBuffer(ox);
                boolean end = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0 && out != null) {
                    AudioSample s = new AudioSample();
                    s.data = new byte[bi.size];
                    out.position(bi.offset);
                    out.get(s.data, 0, bi.size);
                    s.ptsUs = bi.presentationTimeUs;
                    s.flags = bi.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    audio.add(s);
                }
                a.releaseOutputBuffer(ox, false);
                if (end) break;
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("the sound encoder (" + name + ") failed: " + e, e);
        } finally {
            if (a != null) {
                try {
                    a.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    a.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (audioFormat == null || audio.isEmpty()) throw new IOException("the sound encoder gave nothing");
    }

    /** The sound track's samples up to {@code ptsUs} (all of them: Long.MAX_VALUE) into the file. */
    private void writeAudio(long ptsUs) {
        if (audioTrack < 0) return;
        while (!audio.isEmpty() && audio.peekFirst().ptsUs <= ptsUs) {
            AudioSample s = audio.pollFirst();
            audioInfo.set(0, s.data.length, s.ptsUs, s.flags);
            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), audioInfo);
            audioBytes += s.data.length;
        }
    }

    /** The format asked of the encoder: the size, about 0.25 bits a pixel, a key frame a second, BT.709 colours. */
    private static MediaFormat format(int w, int h, int fps, boolean high, int bitRate) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (high) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
        }
        return f;
    }

    /** Bits a second: the sky's grain in the first frames is costly to keep, so a generous 0.25 bits a pixel. */
    static int bitRate(int w, int h, int fps) {
        long b = Math.round(w * (double) h * fps * 0.25);
        return (int) Math.max(4_000_000L, Math.min(16_000_000L, b));
    }

    /** An encoder's name for a w x h video (High profile if asked), or null. */
    static String encoderFor(int w, int h, int fps, boolean high) {
        try {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format(w, h, fps, high, bitRate(w, h, fps)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What was made, for the log: the encoder, frames and size. */
    @Override
    public String note() {
        return String.format(java.util.Locale.US, "%s, %d frames, %.1f MB%s", codecName, frames, bytes / 1e6,
                audioFormat != null ? String.format(java.util.Locale.US, ", sound %.0f kB", audioBytes / 1e3) : "");
    }

    @Override
    public void frame(int[] argb, long ptsUs) throws IOException {
        if (codec == null) throw new IOException("the video encoder is closed");
        if (argb.length != w * h) throw new IOException("a frame of " + argb.length + " pixels for a " + w + "x" + h + " video");
        if (ptsUs <= lastPts) throw new IOException("frames out of order");
        try {
            int ix;
            long t0 = System.currentTimeMillis();
            while ((ix = codec.dequeueInputBuffer(WAIT_US)) < 0) {
                drain(false);
                if (System.currentTimeMillis() - t0 > END_MS) throw new IOException("the video encoder stopped taking frames");
            }
            ByteBuffer bb = codec.getInputBuffer(ix);
            int cap = bb != null ? bb.capacity() : 0;
            Image img = codec.getInputImage(ix);
            if (img == null) throw new IOException("the video encoder takes no pictures");
            fill(img, argb, w, h);
            codec.queueInputBuffer(ix, 0, cap > 0 ? cap : w * h * 3 / 2, ptsUs, 0);
            lastPts = ptsUs;
            frames++;
            drain(false);
        } catch (IllegalStateException e) {
            throw new IOException("the video encoder failed: " + e, e);
        }
    }

    @Override
    public void finish() throws IOException {
        if (codec == null) throw new IOException("the video encoder is closed");
        try {
            int ix;
            long t0 = System.currentTimeMillis();
            while ((ix = codec.dequeueInputBuffer(WAIT_US)) < 0) {
                drain(false);
                if (System.currentTimeMillis() - t0 > END_MS) throw new IOException("the video encoder wouldn't end");
            }
            codec.queueInputBuffer(ix, 0, 0, Math.max(0, lastPts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
            if (!muxing || bytes == 0) throw new IOException("the video encoder gave nothing");
            writeAudio(Long.MAX_VALUE);
            muxer.stop();
            muxing = false;
        } catch (IllegalStateException e) {
            throw new IOException("the video encoder failed at the end: " + e, e);
        } finally {
            close();
        }
    }

    /** Takes what the encoder has ready into the file; {@code toEnd}: everything, until its end of stream. */
    private void drain(boolean toEnd) throws IOException {
        long t0 = System.currentTimeMillis();
        while (true) {
            int ox = codec.dequeueOutputBuffer(info, toEnd ? WAIT_US : 0);
            if (ox == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!toEnd) return;
                if (System.currentTimeMillis() - t0 > END_MS) throw new IOException("the video encoder didn't finish");
                continue;
            }
            if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxing) throw new IOException("the video's format changed part of the way");
                track = muxer.addTrack(codec.getOutputFormat());
                if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat);
                muxer.start();
                muxing = true;
                continue;
            }
            if (ox < 0) continue;
            ByteBuffer out = codec.getOutputBuffer(ox);
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            // the codec's set-up (SPS/PPS) is in the format the muxer was given
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && out != null) {
                if (!muxing) {
                    codec.releaseOutputBuffer(ox, false);
                    throw new IOException("video before its format");
                }
                writeAudio(info.presentationTimeUs);
                out.position(info.offset);
                out.limit(info.offset + info.size);
                muxer.writeSampleData(track, out, info);
                bytes += info.size;
            }
            codec.releaseOutputBuffer(ox, false);
            if (end) return;
        }
    }

    @Override
    public void close() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            codec = null;
        }
        if (muxer != null) {
            if (muxing) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxing = false;
            }
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
            }
            muxer = null;
        }
    }

    /**
     * An ARGB picture into the encoder's YUV 4:2:0 planes, whatever their layout: BT.709 in limited range (16-235,
     * 16-240), each 2 x 2 block's colour averaged.
     */
    static void fill(Image img, final int[] argb, final int w, final int h) {
        Image.Plane[] p = img.getPlanes();
        final ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        final int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        final int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        final int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        Parallel.forRange(h / 2, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                byte[] line = new byte[w];
                ByteBuffer y = yb.duplicate();
                for (int cy = j0; cy < j1; cy++) {
                    for (int dy = 0; dy < 2; dy++) {
                        int row = 2 * cy + dy, o = row * w;
                        for (int x = 0; x < w; x++) line[x] = (byte) luma(argb[o + x]);
                        if (yPix == 1) {
                            y.position(row * yRow);
                            y.put(line, 0, w);
                        } else {
                            for (int x = 0; x < w; x++) yb.put(row * yRow + x * yPix, line[x]);
                        }
                    }
                    int o0 = 2 * cy * w, o1 = o0 + w;
                    for (int cx = 0; cx < w / 2; cx++) {
                        int a = argb[o0 + 2 * cx], b = argb[o0 + 2 * cx + 1], c = argb[o1 + 2 * cx], d = argb[o1 + 2 * cx + 1];
                        int r = ((a >> 16) & 255) + ((b >> 16) & 255) + ((c >> 16) & 255) + ((d >> 16) & 255);
                        int g = ((a >> 8) & 255) + ((b >> 8) & 255) + ((c >> 8) & 255) + ((d >> 8) & 255);
                        int bl = (a & 255) + (b & 255) + (c & 255) + (d & 255);
                        ub.put(cy * uRow + cx * uPix, (byte) cb(r, g, bl));
                        vb.put(cy * vRow + cx * vPix, (byte) cr(r, g, bl));
                    }
                }
            }
        });
    }

    /** BT.709 limited-range luma of an ARGB pixel. */
    static int luma(int v) {
        return ((47 * ((v >> 16) & 255) + 157 * ((v >> 8) & 255) + 16 * (v & 255) + 128) >> 8) + 16;
    }

    /** BT.709 limited-range chroma of a 2 x 2 block, from its colours' sums (four pixels' worth each). */
    static int cb(int r4, int g4, int b4) {
        return clamp(((-26 * r4 - 86 * g4 + 112 * b4 + 512) >> 10) + 128);
    }

    static int cr(int r4, int g4, int b4) {
        return clamp(((112 * r4 - 102 * g4 - 10 * b4 + 512) >> 10) + 128);
    }

    private static int clamp(int v) {
        return v < 16 ? 16 : (v > 240 ? 240 : v);
    }
}
