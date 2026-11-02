package com.brandon.nightsky;

import com.brandon.nightsky.processing.PhotoVideo;

import java.io.File;
import java.io.IOException;

/**
 * Where the photo's video goes (1.8): its frames in order, then {@link #finish} for a complete file; {@link #close}
 * always. {@link Mp4Writer} is the phone's; the off-phone harness has no video encoder and puts in its own.
 */
interface VideoEncoder extends PhotoVideo.Sink {
    void finish() throws IOException;

    /** Lets go of the encoder (a file not finished is left incomplete: delete it). */
    void close();

    /** What was made, for the log. */
    String note();

    /** Makes encoders. */
    interface Factory {
        /** Whether a video this size can be made. */
        boolean supports(int w, int h);

        VideoEncoder open(File out, int w, int h, int fps) throws IOException;
    }
}
