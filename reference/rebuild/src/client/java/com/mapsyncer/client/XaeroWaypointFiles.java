package com.mapsyncer.client;

import java.nio.file.Files;
import java.nio.file.Path;

final class XaeroWaypointFiles {
    private XaeroWaypointFiles() {
    }

    static Path findWaypointsFile(Path worldMapServerDir) {
        if (worldMapServerDir == null) {
            return null;
        }

        Path xaeroDir = worldMapServerDir.getParent();
        if (xaeroDir == null) {
            return null;
        }
        Path gameDir = xaeroDir.getParent();
        if (gameDir == null) {
            return null;
        }

        String serverFolder = worldMapServerDir.getFileName().toString();
        Path minimapWaypoints = gameDir.resolve("minimap").resolve("Multiplayer")
                .resolve(serverFolder).resolve("waypoints.txt");
        if (Files.exists(minimapWaypoints) || Files.exists(minimapWaypoints.getParent())) {
            return minimapWaypoints;
        }

        Path legacy = gameDir.resolve("minimap").resolve(serverFolder).resolve("waypoints.txt");
        if (Files.exists(legacy) || Files.exists(legacy.getParent())) {
            return legacy;
        }

        Path worldMapWaypoints = worldMapServerDir.resolve("waypoints.txt");
        if (Files.exists(worldMapWaypoints) || Files.exists(worldMapWaypoints.getParent())) {
            return worldMapWaypoints;
        }
        return null;
    }
}
