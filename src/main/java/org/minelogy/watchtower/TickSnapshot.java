package org.minelogy.watchtower;

public record TickSnapshot(
        double secondTps,      // 秒级 TPS
        double secondMspt,     // 秒级 MSPT (毫秒)
        double minuteTps,      // 分钟级 TPS (60秒滑动平均)
        double minuteMspt,     // 分钟级 MSPT (60秒滑动平均)
        long timestamp         // 采集时间戳 (毫秒)
) {}