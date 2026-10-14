package com.brandon.nightsky.processing;

/** Progress reporting and cancellation for long processing steps. */
public interface Progress {
    /** @param fraction 0..1 within the current stage */
    void update(String stage, float fraction);

    boolean cancelled();

    Progress NONE = new Progress() {
        @Override
        public void update(String stage, float fraction) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };
}
