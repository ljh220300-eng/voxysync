package com.mapsyncer.util;

import com.mapsyncer.config.ModConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class MapSyncerExecutors {
    private static volatile ExecutorService conversionExecutor;
    private static volatile ExecutorService syncExecutor;

    private MapSyncerExecutors() {
    }

    public static Future<?> submitConversion(Runnable task) {
        return conversionExecutor().submit(task);
    }

    public static Future<?> submitSync(Runnable task) {
        return syncExecutor().submit(task);
    }

    public static void shutdown() {
        shutdownExecutor(conversionExecutor);
        shutdownExecutor(syncExecutor);
        conversionExecutor = null;
        syncExecutor = null;
    }

    private static ExecutorService conversionExecutor() {
        ExecutorService current = conversionExecutor;
        if (current == null || current.isShutdown()) {
            synchronized (MapSyncerExecutors.class) {
                current = conversionExecutor;
                if (current == null || current.isShutdown()) {
                    int configured = Math.max(1, ModConfig.SERVER.maxConcurrentRegions);
                    int cpuBound = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                    int threads = Math.max(1, Math.min(configured, cpuBound));
                    conversionExecutor = current = Executors.newFixedThreadPool(
                            threads, daemonFactory("mapsyncer-conversion", Thread.NORM_PRIORITY - 1));
                }
            }
        }
        return current;
    }

    private static ExecutorService syncExecutor() {
        ExecutorService current = syncExecutor;
        if (current == null || current.isShutdown()) {
            synchronized (MapSyncerExecutors.class) {
                current = syncExecutor;
                if (current == null || current.isShutdown()) {
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
                    syncExecutor = current = Executors.newFixedThreadPool(
                            threads, daemonFactory("mapsyncer-sync", Thread.NORM_PRIORITY - 1));
                }
            }
        }
        return current;
    }

    private static ThreadFactory daemonFactory(String name, int priority) {
        return new ThreadFactory() {
            private int index = 0;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + (++index));
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority)));
                return thread;
            }
        };
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
