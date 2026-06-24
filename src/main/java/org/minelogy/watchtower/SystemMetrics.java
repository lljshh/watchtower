package org.minelogy.watchtower;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import static org.minelogy.watchtower.Watchtower.LOGGER;

public class SystemMetrics {
    double cpuLoad;
    double hostMemoryUsage;
    long heapUsed;
    long heapMax;
    long heapCommitted;
    private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public SystemMetrics(double cpuLoad, double hostMemoryUsage, long heapUsed, long heapMax, long heapCommitted) {
        this.cpuLoad = cpuLoad;
        this.hostMemoryUsage = hostMemoryUsage;
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.heapCommitted = heapCommitted;
    }


    public static SystemMetrics getSystemMetrics() {
        double cpuLoad = 0.0;
        long totalPhysical = -1, freePhysical = -1;
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                cpuLoad = sunOsBean.getProcessCpuLoad();
                if (cpuLoad < 0) cpuLoad = 0.0;
                totalPhysical = sunOsBean.getTotalMemorySize();
                freePhysical = sunOsBean.getFreeMemorySize();
            }
        } catch (NoClassDefFoundError ignored) {
            LOGGER.debug("com.sun.management.OperatingSystemMXBean not available, CPU/memory metrics disabled");
        }
        double hostMemoryUsage = totalPhysical > 0 ?
                1.0 - (double) freePhysical / totalPhysical : -1.0;

        // JVM 堆内存
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long heapCommitted = heapUsage.getCommitted();

        return new SystemMetrics(
                cpuLoad,
                hostMemoryUsage,
                heapUsed,
                heapMax,
                heapCommitted
        );
    }
}
