package com.brandon.nightsky;

import android.app.Application;

/** The app's process: the stop recorder starts with it, so a crash anywhere (a session, a plan, the widget) is written down. */
public final class NightSkyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        StopRecorder.install(this);
    }
}
