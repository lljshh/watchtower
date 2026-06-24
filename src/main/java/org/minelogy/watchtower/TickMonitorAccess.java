package org.minelogy.watchtower;

public interface TickMonitorAccess {
    TickSnapshot getSnapshot();
    boolean isReady();
}