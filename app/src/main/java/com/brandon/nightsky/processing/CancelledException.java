package com.brandon.nightsky.processing;

/** Thrown when the user cancels processing. */
public final class CancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("cancelled");
    }
}
