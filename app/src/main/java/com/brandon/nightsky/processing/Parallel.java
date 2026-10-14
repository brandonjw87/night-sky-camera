package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Minimal parallel-for over an index range, backed by one shared fixed thread pool.
 * Tasks must not call forRange() recursively (the pool is fixed-size).
 */
public final class Parallel {
    public interface Range {
        void run(int start, int end) throws Exception;
    }

    private static ExecutorService pool;
    private static int nThreads;

    private Parallel() {}

    private static synchronized void ensure() {
        if (pool != null) return;
        int cores = Runtime.getRuntime().availableProcessors();
        nThreads = Math.max(1, Math.min(8, cores));
        pool = Executors.newFixedThreadPool(nThreads, new ThreadFactory() {
            private int n = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nightsky-worker-" + (n++));
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static synchronized int threads() {
        ensure();
        return nThreads;
    }

    public static void forRange(int n, Range task) {
        forRange(n, 1, task);
    }

    /** Splits [0, n) into chunks of at least {@code grain} items and runs them on the pool. */
    public static void forRange(int n, int grain, final Range task) {
        if (n <= 0) return;
        ensure();
        int chunks = Math.min(n, nThreads * 3);
        if (grain > 1) chunks = Math.min(chunks, Math.max(1, n / grain));
        if (chunks <= 1) {
            try {
                task.run(0, n);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }
        final int per = (n + chunks - 1) / chunks;
        List<Future<Void>> futures = new ArrayList<Future<Void>>(chunks);
        for (int c = 0; c < chunks; c++) {
            final int s = c * per;
            final int e = Math.min(n, s + per);
            if (s >= e) break;
            futures.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    task.run(s, e);
                    return null;
                }
            }));
        }
        Throwable err = null;
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ex) {
                if (err == null) err = ex.getCause();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (err == null) err = ie;
            }
        }
        if (err != null) {
            if (err instanceof RuntimeException) throw (RuntimeException) err;
            if (err instanceof Error) throw (Error) err;
            throw new RuntimeException(err);
        }
    }
}
