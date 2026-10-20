package com.mail.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 系统状态快照：CPU、内存、磁盘、网络、JVM 五个板块。
 * 由 {@link SystemMetricsService} 每次调用即时采样，字段可为 null（不可用指标）。
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemStatus {

    private long sampledAt;

    private Cpu cpu;
    private Memory memory;
    private List<Disk> disks;
    private List<NetInterface> network;
    private Jvm jvm;

    @Getter
    @Setter
    public static class Cpu {
        private int cores;
        /** 系统 CPU 使用率百分比 0-100，不可用时 null */
        private Double systemLoadPct;
        /** 本进程 CPU 使用率百分比 */
        private Double processLoadPct;
        /** 1/5/15 分钟负载（Linux/macOS 有效，Windows 为 null） */
        private Double load1;
        private Double load5;
        private Double load15;
    }

    @Getter
    @Setter
    public static class Memory {
        private long totalBytes;
        private long freeBytes;
        private long usedBytes;
        /** 系统内存使用率百分比 */
        private Integer usedPct;
        /** OS 物理内存的可用/总量；某些平台不可用时只填 JVM 部分 */
        private Long osAvailableBytes;
    }

    @Getter
    @Setter
    public static class Disk {
        private String path;
        private String label;
        private long totalBytes;
        private long usableBytes;
        private long usedBytes;
        private Integer usedPct;
    }

    @Getter
    @Setter
    public static class NetInterface {
        private String name;
        private String displayName;
        private boolean up;
        private boolean loopback;
        private List<String> addresses;
    }

    @Getter
    @Setter
    public static class Jvm {
        private String name;
        private String vendor;
        private String version;
        private long uptimeMs;
        private long startTime;
        private int threads;
        private long heapUsedBytes;
        private long heapCommittedBytes;
        private long heapMaxBytes;
        private Map<String, Long> pools;
    }
}
