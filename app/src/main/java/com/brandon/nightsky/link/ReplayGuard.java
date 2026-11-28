package com.brandon.nightsky.link;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps an old message from being played again (2.5): a message is taken once, by its id, and only while it's fresh.
 * Somebody who copied a sealed "stop" off the relay can't stop tomorrow's session with it.
 */
public final class ReplayGuard {
    private final long windowMs;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>();

    /** Messages sent more than {@code windowMs} before now (or after it: a clock ahead) are refused. */
    public ReplayGuard(long windowMs) {
        this.windowMs = windowMs;
    }

    /** True the first time message {@code mid}, sent at {@code ts}, is seen while fresh; false for a copy or a stale one. */
    public synchronized boolean fresh(String mid, long ts, long now) {
        if (mid == null || mid.isEmpty() || mid.length() > 64) return false;
        if (Math.abs(now - ts) > windowMs) return false;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > 2 * windowMs || seen.size() > 4096) it.remove();
            else break;
        }
        if (seen.containsKey(mid)) return false;
        seen.put(mid, now);
        return true;
    }
}
