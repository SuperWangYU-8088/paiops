package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HostResourceCheckNodeExecutor implements NodeExecutor {

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        java.lang.management.OperatingSystemMXBean standardBean =
                ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("checkedAt", Instant.now().toString());
        output.put("host", System.getenv().getOrDefault("HOSTNAME", "local"));
        output.put("availableProcessors", standardBean.getAvailableProcessors());
        output.put("systemLoadAverage", standardBean.getSystemLoadAverage());

        if (standardBean instanceof com.sun.management.OperatingSystemMXBean bean) {
            output.put("cpuUsagePercent", roundPercent(bean.getCpuLoad()));
            output.put("processCpuUsagePercent", roundPercent(bean.getProcessCpuLoad()));
            output.put("totalMemoryBytes", bean.getTotalMemorySize());
            output.put("freeMemoryBytes", bean.getFreeMemorySize());
            output.put("memoryUsagePercent", memoryUsage(bean.getTotalMemorySize(), bean.getFreeMemorySize()));
        }

        List<Map<String, Object>> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("path", root.getAbsolutePath());
            disk.put("totalBytes", root.getTotalSpace());
            disk.put("freeBytes", root.getUsableSpace());
            disk.put("usagePercent", memoryUsage(root.getTotalSpace(), root.getUsableSpace()));
            disks.add(disk);
        }
        output.put("disks", disks);
        output.put("healthy", isHealthy(node, output));
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "host_resource_check";
    }

    private boolean isHealthy(WorkflowNode node, Map<String, Object> output) {
        int maxCpu = OpsHttpSupport.integer(node, "maxCpuPercent", 90, 1, 100);
        int maxMemory = OpsHttpSupport.integer(node, "maxMemoryPercent", 90, 1, 100);
        double cpu = output.get("cpuUsagePercent") instanceof Number number ? number.doubleValue() : 0;
        double memory = output.get("memoryUsagePercent") instanceof Number number ? number.doubleValue() : 0;
        return cpu <= maxCpu && memory <= maxMemory;
    }

    private double roundPercent(double ratio) {
        if (ratio < 0) {
            return 0;
        }
        return Math.round(ratio * 10_000.0) / 100.0;
    }

    private double memoryUsage(long total, long free) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(((double) (total - free) / total) * 10_000.0) / 100.0;
    }
}
