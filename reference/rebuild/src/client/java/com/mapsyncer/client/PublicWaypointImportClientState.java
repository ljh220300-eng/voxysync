package com.mapsyncer.client;

import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;

import java.util.List;

final class PublicWaypointImportClientState {
    enum Status {
        IDLE,
        SCANNING,
        READY,
        NO_FILE,
        EMPTY,
        FAILED,
        ADDING,
        ADDED,
        UPDATED,
        PERMISSION_DENIED
    }

    private static volatile Status status = Status.IDLE;
    private static volatile List<LocalXaeroWaypointScanner.LocalWaypoint> waypoints = List.of();
    private static volatile int skippedCount;
    private static volatile String detail = "";
    private static volatile PacketHandler.PublicWaypoint pendingWaypoint;
    private static volatile long updatedAtMillis;

    private PublicWaypointImportClientState() {
    }

    static Status getStatus() {
        return status;
    }

    static List<LocalXaeroWaypointScanner.LocalWaypoint> getWaypoints() {
        return waypoints;
    }

    static int getSkippedCount() {
        return skippedCount;
    }

    static String getDetail() {
        return detail;
    }

    static long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    static void setScanning() {
        set(Status.SCANNING, List.of(), 0, "", null);
    }

    static void setScanResult(LocalXaeroWaypointScanner.Result result) {
        Status nextStatus = switch (result.status()) {
            case READY -> Status.READY;
            case NO_FILE -> Status.NO_FILE;
            case EMPTY -> Status.EMPTY;
            case FAILED -> Status.FAILED;
        };
        set(nextStatus, result.waypoints(), result.skippedCount(), result.detail(), null);
    }

    static void setAdding(PacketHandler.PublicWaypoint waypoint) {
        set(Status.ADDING, waypoints, skippedCount, waypoint == null ? "" : waypoint.name(), waypoint);
    }

    static void handleAddResult(PacketHandler.PublicWaypointAddResultPayload payload) {
        Minecraft.getInstance().execute(() -> {
            status = switch (payload.status()) {
                case "added" -> Status.ADDED;
                case "updated" -> Status.UPDATED;
                case "permission_denied" -> Status.PERMISSION_DENIED;
                default -> Status.FAILED;
            };
            detail = payload.name();
            pendingWaypoint = null;
            updatedAtMillis = System.currentTimeMillis();
        });
    }

    static void reset() {
        status = Status.IDLE;
        waypoints = List.of();
        skippedCount = 0;
        detail = "";
        pendingWaypoint = null;
        updatedAtMillis = 0;
    }

    static boolean isPending(PacketHandler.PublicWaypoint waypoint) {
        PacketHandler.PublicWaypoint pending = pendingWaypoint;
        return pending != null && waypoint != null
                && pending.dimension().equals(waypoint.dimension())
                && pending.name().equals(waypoint.name())
                && pending.x() == waypoint.x()
                && pending.y() == waypoint.y()
                && pending.z() == waypoint.z();
    }

    private static void set(Status newStatus, List<LocalXaeroWaypointScanner.LocalWaypoint> newWaypoints,
                            int newSkippedCount, String newDetail, PacketHandler.PublicWaypoint newPendingWaypoint) {
        Minecraft.getInstance().execute(() -> {
            status = newStatus;
            waypoints = List.copyOf(newWaypoints == null ? List.of() : newWaypoints);
            skippedCount = Math.max(0, newSkippedCount);
            detail = newDetail == null ? "" : newDetail;
            pendingWaypoint = newPendingWaypoint;
            updatedAtMillis = System.currentTimeMillis();
        });
    }
}
