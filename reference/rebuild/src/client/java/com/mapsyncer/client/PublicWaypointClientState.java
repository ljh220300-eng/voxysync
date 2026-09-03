package com.mapsyncer.client;

import net.minecraft.client.Minecraft;

final class PublicWaypointClientState {
    enum Status {
        WAITING,
        SYNCING,
        SYNCED,
        UP_TO_DATE,
        MISSING_DIR,
        FILE_BUSY,
        FAILED
    }

    private static volatile Status status = Status.WAITING;
    private static volatile int count;
    private static volatile long updatedAtMillis;

    private PublicWaypointClientState() {
    }

    static Status getStatus() {
        return status;
    }

    static int getCount() {
        return count;
    }

    static long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    static void set(Status newStatus, int newCount) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            status = newStatus;
            count = Math.max(0, newCount);
            updatedAtMillis = System.currentTimeMillis();
        });
    }

    static void reset() {
        status = Status.WAITING;
        count = 0;
        updatedAtMillis = 0;
    }
}
