package com.opsmonitor.service;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.monitor.ExporterManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.monitor.GrafanaManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统审计服务 V6
 *
 * 5 维度评分（每项满分 20 分，总分 100）：
 *   1. Prometheus Targets  — Prometheus 是否运行 + targets 文件数
 *   2. Exporter Health     — UP/DOWN/PENDING 统计
 *   3. Alert Rules         — alert.rules.yml + alertmanager.yml 是否存在
 *   4. Dashboards          — V6 三个仪表盘文件是否存在（infra/service-health/middleware）
 *   5. Topology            — 已注册 Exporter 的 project/service 标签覆盖率
 *
 * V6 修复：Dashboard 检查从旧的 5 个文件名改为新的 3 个文件名
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAuditService {

    private final OpsMonitorProperties properties;
    private final PrometheusManager    prometheusManager;
    private final GrafanaManager       grafanaManager;
    private final ExporterManager      exporterManager;

    /** V6 当前的三个仪表盘文件名 */
    private static final String[] V6_DASHBOARDS = {
            "infra-overview.json",
            "service-health.json",
            "middleware.json"
    };

    public AuditReport runAudit() {
        List<AuditItem> items  = new ArrayList<>();
        List<String>    issues = new ArrayList<>();
        int totalScore = 0;

        // ── 1. Prometheus Targets ─────────────────────────────────────
        {
            boolean promUp = prometheusManager.isRunning();
            int targetFiles = countTargetFiles();
            int score;
            String status;
            String detail;
            if (promUp && targetFiles > 0) {
                score  = 20; status = "OK";
                detail = "Prometheus=UP, targets 文件=" + targetFiles;
            } else if (promUp) {
                score  = 15; status = "WARN";
                detail = "Prometheus=UP, 但无 targets 文件（尚未注册 Exporter）";
                issues.add("尚未注册任何 Exporter");
            } else {
                score  = 0; status = "DOWN";
                detail = "Prometheus 未运行";
                issues.add("Prometheus 未运行，请检查 Docker 容器 ops-prometheus");
            }
            items.add(new AuditItem("Prometheus Targets", status, 20, score, detail));
            totalScore += score;
        }

        // ── 2. Exporter Health ────────────────────────────────────────
        {
            var exporters = exporterManager.listExporters();
            int total   = exporters.size();
            int up      = (int) exporters.stream().filter(e -> "running".equals(e.getState()) || "remote".equals(e.getState())).count();
            int down    = (int) exporters.stream().filter(e -> "exited".equals(e.getState())).count();
            int pending = total - up - down;
            int score;
            String status;
            String detail = String.format("UP=%d DOWN=%d PENDING=%d 共%d", up, down, pending, total);
            if (total == 0) {
                score = 20; status = "OK";
                detail = "无注册 Exporter（正常初始状态）";
            } else if (down == 0) {
                score = 20; status = "OK";
            } else if (down <= total / 3) {
                score = 12; status = "WARN";
                issues.add(down + " 个 Exporter 离线：" + detail);
            } else {
                score = 4; status = "DOWN";
                issues.add("超过 1/3 Exporter 离线：" + detail);
            }
            items.add(new AuditItem("Exporter Health", status, 20, score, detail));
            totalScore += score;
        }

        // ── 3. Alert Rules ────────────────────────────────────────────
        {
            String workDir = properties.getCompose().getWorkDir();
            boolean rulesOk = Files.exists(Paths.get(workDir, "alert.rules.yml"));
            boolean amOk    = Files.exists(Paths.get(workDir, "alertmanager.yml"));
            int score = (rulesOk ? 10 : 0) + (amOk ? 10 : 0);
            String status = (score == 20) ? "OK" : "WARN";
            String detail = "rules=" + rulesOk + ", alertmanager=" + amOk;
            if (!rulesOk) issues.add("缺少 alert.rules.yml");
            if (!amOk)    issues.add("缺少 alertmanager.yml");
            items.add(new AuditItem("Alert Rules", status, 20, score, detail));
            totalScore += score;
        }

        // ── 4. Dashboards (V6 三文件) ─────────────────────────────────
        {
            String workDir  = properties.getCompose().getWorkDir();
            Path   dashDir  = Paths.get(workDir, "grafana", "provisioning", "dashboards");
            boolean grafanaUp = grafanaManager.isRunning();
            int found = 0;
            List<String> missing = new ArrayList<>();
            for (String fname : V6_DASHBOARDS) {
                if (Files.exists(dashDir.resolve(fname))) {
                    found++;
                } else {
                    missing.add(fname);
                    issues.add("缺少 Dashboard: " + fname);
                }
            }
            int score;
            String status;
            String detail = String.format("Grafana=%s, Dashboard=%d/%d",
                    grafanaUp ? "UP" : "DOWN", found, V6_DASHBOARDS.length);
            if (!grafanaUp) {
                score = 4; status = "WARN";
                issues.add("Grafana 未运行");
            } else if (found == V6_DASHBOARDS.length) {
                score = 20; status = "OK";
            } else if (found > 0) {
                score = 8 + (found * 4); status = "WARN";
            } else {
                score = 4; status = "WARN";
            }
            items.add(new AuditItem("Dashboards", status, 20, score, detail));
            totalScore += score;
        }

        // ── 5. Topology (project/service 标签覆盖率) ──────────────────
        //
        // 设计说明：project/service 是可选的元数据标签，用于 Grafana 筛选。
        // 未填写时系统功能正常，仅影响拓扑视图的聚合效果。
        // 评分策略（调整后）：
        //   80%+ 覆盖率 → 20分（完整拓扑信息）
        //   50%~80%    → 14分（部分覆盖）
        //   0%~50%     → 8分（提示建议，但不重度扣分，因为是可选字段）
        //   无 Exporter → 20分（跳过）
        {
            var exporters = exporterManager.listExporters();
            int total    = exporters.size();
            int withProj = (int) exporters.stream()
                    .filter(e -> e.getProject() != null && !e.getProject().isBlank()).count();
            int withSvc  = (int) exporters.stream()
                    .filter(e -> e.getService() != null && !e.getService().isBlank()).count();
            int score;
            String status;
            String detail;
            if (total == 0) {
                score = 20; status = "OK";
                detail = "无 Exporter，跳过标签检查";
            } else {
                detail = String.format("project标签=%d/%d, service标签=%d/%d",
                        withProj, total, withSvc, total);
                double projRate = (double) withProj / total;
                double svcRate  = (double) withSvc  / total;
                if (projRate >= 0.8 && svcRate >= 0.8) {
                    score = 20; status = "OK";
                } else if (projRate >= 0.5 || svcRate >= 0.5) {
                    score = 14; status = "WARN";
                    if (withProj < total) issues.add(String.format(
                            "建议补充 project 标签（当前 %d/%d 已填写），可提升 Grafana 拓扑筛选能力",
                            withProj, total));
                    if (withSvc < total) issues.add(String.format(
                            "建议补充 service 标签（当前 %d/%d 已填写），可提升 Grafana 拓扑筛选能力",
                            withSvc, total));
                } else {
                    // project/service 是可选字段，0% 不扣满分，给基础分 8 分并提示
                    score = 8; status = "WARN";
                    if (withProj < total) issues.add(String.format(
                            "建议在 Exporter 管理中为 %d 个 Exporter 填写 project 标签，以启用按项目聚合筛选",
                            total - withProj));
                    if (withSvc < total) issues.add(String.format(
                            "建议在 Exporter 管理中为 %d 个 Exporter 填写 service 标签，以启用按服务聚合筛选",
                            total - withSvc));
                }
            }
            items.add(new AuditItem("Topology", status, 20, score, detail));
            totalScore += score;
        }

        // ── 计算等级 ──────────────────────────────────────────────────
        String grade;
        if      (totalScore >= 95) grade = "A";
        else if (totalScore >= 80) grade = "B";
        else if (totalScore >= 60) grade = "C";
        else                       grade = "D";

        return new AuditReport(totalScore, grade, items, issues);
    }

    private int countTargetFiles() {
        try {
            Path targetsDir = Paths.get(properties.getCompose().getWorkDir(), "targets");
            if (!Files.isDirectory(targetsDir)) return 0;
            return (int) Files.list(targetsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try { return Files.size(p) > 2; } catch (Exception e) { return false; }
                    })
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ── 数据模型 ─────────────────────────────────────────────────────

    @Data
    public static class AuditReport {
        private final int         healthScore;
        private final String      grade;
        private final List<AuditItem> items;
        private final List<String>    issues;
    }

    @Data
    public static class AuditItem {
        private final String component;
        private final String status;
        private final int    weight;
        private final int    score;
        private final String detail;
    }
}