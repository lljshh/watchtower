package org.minelogy.watchtower;

public record SecondBucket(
        long tickCount,
        long elapsedNanos,
        long accumulatedTickNanos,
        long timestampNanos
) {}