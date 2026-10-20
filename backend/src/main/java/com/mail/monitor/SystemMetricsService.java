package com.mail.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集 CPU / 内存 / 磁盘 / 网络 / JVM 运行指标。全部为瞬时采样，无副作用。
 */
@Slf4j
@Service
public class SystemMetricsService {

    public SystemStatus collect() {
        SystemStatus s = new SystemStatus();
        s.setSampledAt(System.currentTimeMillis());
        s.setCpu(collectCpu());
        s.setMemory(collectMemory());
        s.setDisks(collectDisks());
        s.setNetwork(collectNetwork());
        s.setJvm(collectJvm());
        return s;
    }

    private SystemStatus.Cpu collectCpu() {
        SystemStatus.Cpu cpu = new SystemStatus.Cpu();
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        cpu.setCores(base.getAvailableProcessors());
        cpu.setLoad1(base.getSystemLoadAverage() >= 0 ? base.getSystemLoadAverage() : null);
        if (base instanceof com.sun.management.OperatingSystemMXBean os) {
            double sys = os.getCpuLoad();       // -1 表示不可用
            double proc = os.getProcessCpuLoad();
            cpu.setSystemLoadPct(sys >= 0 ? round1(sys * 100) : null);
            cpu.setProcessLoadPct(proc >= 0 ? round1(proc * 100) : null);
        }
        return cpu;
    }

    private SystemStatus.Memory collectMemory() {
        SystemStatus.Memory m = new SystemStatus.Memory();
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            m.setTotalBytes(total);
            m.setFreeBytes(free);
            m.setOsAvailableBytes(os.getFreeMemorySize());
            long used = total - free;
            m.setUsedBytes(used);
            if (total > 0) m.setUsedPct((int) Math.round(100.0 * used / total));
        }
        return m;
    }

    private List<SystemStatus.Disk> collectDisks() {
        List<SystemStatus.Disk> out = new ArrayList<>();
        for (File root : File.listRoots()) {
            long total = root.getTotalSpace();
            long usable = root.getUsableSpace();
            if (total <= 0) continue;   // 跳过无媒体可移动盘等
            SystemStatus.Disk d = new SystemStatus.Disk();
            d.setPath(root.getPath());
            d.setLabel(root.getPath());
            d.setTotalBytes(total);
            d.setUsableBytes(usable);
            d.setUsedBytes(total - usable);
            d.setUsedPct((int) Math.round(100.0 * (total - usable) / total));
            out.add(d);
        }
        return out;
    }

    private List<SystemStatus.NetInterface> collectNetwork() {
        List<SystemStatus.NetInterface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                SystemStatus.NetInterface ni = new SystemStatus.NetInterface();
                ni.setName(nif.getName());
                ni.setDisplayName(nif.getDisplayName());
                ni.setLoopback(nif.isLoopback());
                ni.setUp(nif.isUp());
                List<String> addrs = new ArrayList<>();
                Enumeration<java.net.InetAddress> ips = nif.getInetAddresses();
                while (ips.hasMoreElements()) addrs.add(ips.nextElement().getHostAddress());
                ni.setAddresses(addrs);
                out.add(ni);
            }
        } catch (SocketException | Error e) {
            log.debug("网络接口采集失败: {}", e.getMessage());
        }
        return out;
    }

    private SystemStatus.Jvm collectJvm() {
        SystemStatus.Jvm j = new SystemStatus.Jvm();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        j.setName(rt.getVmName());
        j.setVendor(rt.getVmVendor());
        j.setVersion(rt.getVmVersion());
        j.setUptimeMs(rt.getUptime());
        j.setStartTime(rt.getStartTime());
        j.setThreads(ManagementFactory.getThreadMXBean().getThreadCount());

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        j.setHeapUsedBytes(heap.getUsed());
        j.setHeapCommittedBytes(heap.getCommitted());
        j.setHeapMaxBytes(heap.getMax());

        Map<String, Long> pools = new LinkedHashMap<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            try {
                pools.put(p.getName(), p.getUsage().getUsed());
            } catch (Exception ignore) {
            }
        }
        j.setPools(pools);
        return j;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
