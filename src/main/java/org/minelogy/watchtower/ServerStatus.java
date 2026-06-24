package org.minelogy.watchtower;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

import static org.minelogy.watchtower.Watchtower.LOGGER;

public class ServerStatus {
    public record Status(long timestamp, int online, int max, double tps, double mspt,
                    double cpuLoad, double hostMemoryUsage,
                    long heapUsed, long heapMax, long heapCommitted) {}
    private static Status getStatus(boolean now, SystemMetrics sys) {

        final MinecraftServer srv = Watchtower.server;
        if (srv == null) {
            LOGGER.debug("Server not available");
            return null;
        }
        if (!(srv instanceof TickMonitorAccess access)) {
            LOGGER.error("Server does not implement TickMonitorAccess");
            return null;
        }
        TickSnapshot tick;
        try {
            tick = access.getSnapshot();
            if (tick == null) {
                LOGGER.warn("Tick snapshot not ready yet");
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get tick snapshot", e);
            return null;
        }
        return new Status(System.currentTimeMillis(),
                Watchtower.serverPlayers.size(),
                Watchtower.serverMaxPlayers,
                now ? tick.secondTps() : tick.minuteTps(),
                now ? tick.secondMspt() : tick.minuteMspt(),
                sys.cpuLoad,
                sys.hostMemoryUsage,
                sys.heapUsed,
                sys.heapMax,
                sys.heapCommitted);
    }
    public static Status getStatus(SystemMetrics sys) {
        return getStatus(false, sys);
    }
    public static Status getNowStatus(SystemMetrics sys) {
        return getStatus(true, sys);
    }
    public record PlayerStatus(int count, List<String> players) {}
    public static PlayerStatus getPlayerStatus() {
        List<String> players = new ArrayList<>(Watchtower.serverPlayers);
        return new PlayerStatus(players.size(), players);
    }
}
