package com.opsmonitor.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Grafana Dashboard 生成器 V6 — 精简三层体系
 *
 * V6 架构：3 个仪表盘，按职责清晰划分
 *   1. 📊 基础设施总览 (infra-overview)    — CPU/内存/磁盘/网络，Linux+Windows 双平台
 *   2. 🔌 服务健康总览 (service-health)    — 所有 Exporter 在线状态 + 历史趋势
 *   3. 🗄️ 数据库与中间件 (middleware)      — Redis/MySQL/PG/Nginx 关键指标
 *
 * 实现说明：
 *   - 使用 ObjectMapper 写入 JSON（而非 Java text block），彻底避免转义问题
 *   - JSON 内容以 Java String 常量存储，已通过 Python json.loads() 验证
 *   - generateAllDashboards() 先写入新文件，再清理废弃旧文件（防止 Grafana 看到空目录）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardGenerator {

    private final OpsMonitorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── V6 仪表盘 JSON 常量（已通过 JSON 验证，使用 ObjectMapper 写入确保格式正确）──

    /** 基础设施总览：CPU/内存/磁盘/网络，支持 Linux node_exporter + Windows windows_exporter */
    private static final String INFRA_JSON =
            "{\"uid\":\"ops-infra-overview\",\"title\":\"📊 Sentinel — 基础设施总览\",\"tags\":[\"ops-monitor\",\"sentinel\",\"infra\"],\"timezone\":\"browser\",\"refresh\":\"30s\",\"time\":{\"from\":\"now-1h\",\"to\":\"now\"},\"schemaVersion\":39,\"version\":7,\"templating\":{\"list\":[{\"name\":\"server_name\",\"type\":\"query\",\"label\":\"🖥 服务器\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"query\":\"label_values(up{managed_by=\\\"ops-monitor\\\"},server_name)\",\"refresh\":2,\"includeAll\":true,\"allValue\":\".*\",\"multi\":true}]},\"panels\":[{\"id\":1,\"type\":\"row\",\"title\":\"🔥 L1 决策区 — 主机健康总览\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":0},\"collapsed\":false},{\"id\":2,\"title\":\"🖥 主机 CPU 健康蜂窝（颜色=CPU使用率）\",\"type\":\"stat\",\"description\":\"每格代表一台主机，颜色表示 CPU 使用率。绿<70% 橙70-85% 红>85%。快速识别高负载主机。\",\"gridPos\":{\"h\":6,\"w\":10,\"x\":0,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\",\"textMode\":\"value_and_name\",\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"100 - (avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100)\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"},{\"expr\":\"100 - (sum by(server_name)(rate(windows_cpu_time_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) / sum by(server_name)(rate(windows_cpu_time_total{server_name=~\\\"$server_name\\\"}[5m])) * 100)\",\"legendFormat\":\"{{server_name}} (Win)\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"decimals\":1,\"thresholds\":{\"mode\":\"absolute\",\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"orange\",\"value\":70},{\"color\":\"red\",\"value\":85}]},\"color\":{\"mode\":\"thresholds\"}}}},{\"id\":3,\"title\":\"🧠 主机内存蜂窝（颜色=内存使用率）\",\"type\":\"stat\",\"description\":\"每格代表一台主机，颜色表示内存使用率。绿<70% 橙70-85% 红>85%。\",\"gridPos\":{\"h\":6,\"w\":10,\"x\":10,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\",\"textMode\":\"value_and_name\",\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"(1 - node_memory_MemAvailable_bytes{server_name=~\\\"$server_name\\\"} / node_memory_MemTotal_bytes{server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"},{\"expr\":\"(1 - windows_os_physical_memory_free_bytes{server_name=~\\\"$server_name\\\"} / windows_cs_physical_memory_bytes{server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}} (Win)\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"decimals\":1,\"thresholds\":{\"mode\":\"absolute\",\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"orange\",\"value\":70},{\"color\":\"red\",\"value\":85}]},\"color\":{\"mode\":\"thresholds\"}}}},{\"id\":4,\"title\":\"✅ 在线主机\",\"type\":\"stat\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":20,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\"},\"targets\":[{\"expr\":\"count(up{exporter_type=~\\\"node|windows\\\",managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) or vector(0)\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"fixed\",\"fixedColor\":\"green\"}}}},{\"id\":5,\"title\":\"🔴 主机离线\",\"type\":\"stat\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":20,\"y\":4},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\"},\"targets\":[{\"expr\":\"count(up{exporter_type=~\\\"node|windows\\\",managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 0) or vector(0)\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"red\",\"value\":1}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"全部在线 ✓\",\"color\":\"green\"}}}]}}},{\"id\":10,\"type\":\"row\",\"title\":\"🟩 L2 运维值班层 — CPU & 内存热力图\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":7},\"collapsed\":false},{\"id\":11,\"title\":\"CPU 使用率热力图（多实例分布）\",\"type\":\"heatmap\",\"description\":\"Y轴：CPU使用率区间。X轴：时间。颜色深度：有多少台主机落在该区间。深红=多台主机高负载，危险。\",\"gridPos\":{\"h\":8,\"w\":12,\"x\":0,\"y\":8},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"100 - (avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100)\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"}],\"options\":{\"calculate\":false,\"yAxis\":{\"unit\":\"percent\",\"decimals\":0},\"color\":{\"mode\":\"scheme\",\"scheme\":\"Spectral\",\"reverse\":true,\"steps\":64},\"cellGap\":1,\"tooltip\":{\"mode\":\"single\"},\"legend\":{\"show\":true}},\"fieldConfig\":{\"defaults\":{\"custom\":{\"hideFrom\":{\"legend\":false,\"tooltip\":false,\"viz\":false}}}}},{\"id\":12,\"title\":\"内存使用率热力图（多实例分布）\",\"type\":\"heatmap\",\"description\":\"Y轴：内存使用率区间。X轴：时间。颜色深度表示集群内存压力分布。\",\"gridPos\":{\"h\":8,\"w\":12,\"x\":12,\"y\":8},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"(1 - node_memory_MemAvailable_bytes{server_name=~\\\"$server_name\\\"} / node_memory_MemTotal_bytes{server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"}],\"options\":{\"calculate\":false,\"yAxis\":{\"unit\":\"percent\",\"decimals\":0},\"color\":{\"mode\":\"scheme\",\"scheme\":\"Spectral\",\"reverse\":true,\"steps\":64},\"cellGap\":1,\"tooltip\":{\"mode\":\"single\"},\"legend\":{\"show\":true}},\"fieldConfig\":{\"defaults\":{\"custom\":{\"hideFrom\":{\"legend\":false,\"tooltip\":false,\"viz\":false}}}}},{\"id\":20,\"type\":\"row\",\"title\":\"🟩 L2 相关性视图 — CPU / 内存趋势\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":16},\"collapsed\":false},{\"id\":21,\"title\":\"CPU 使用率趋势\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":0,\"y\":17},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"100 - (avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100)\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"},{\"expr\":\"100 - (sum by(server_name)(rate(windows_cpu_time_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) / sum by(server_name)(rate(windows_cpu_time_total{server_name=~\\\"$server_name\\\"}[5m])) * 100)\",\"legendFormat\":\"{{server_name}} (Win)\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"custom\":{\"lineWidth\":2,\"fillOpacity\":8},\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"orange\",\"value\":70},{\"color\":\"red\",\"value\":85}]},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"},\"legend\":{\"displayMode\":\"list\",\"placement\":\"bottom\"}}},{\"id\":22,\"title\":\"内存使用趋势\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":12,\"y\":17},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"(1 - node_memory_MemAvailable_bytes{server_name=~\\\"$server_name\\\"} / node_memory_MemTotal_bytes{server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"},{\"expr\":\"(1 - windows_os_physical_memory_free_bytes{server_name=~\\\"$server_name\\\"} / windows_cs_physical_memory_bytes{server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}} (Win)\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"custom\":{\"lineWidth\":2,\"fillOpacity\":8},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"},\"legend\":{\"displayMode\":\"list\",\"placement\":\"bottom\"}}},{\"id\":30,\"type\":\"row\",\"title\":\"🟨 L2 磁盘预测 & 网络\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":24},\"collapsed\":false},{\"id\":31,\"title\":\"💾 磁盘预计填满时间（predict_linear）\",\"type\":\"gauge\",\"description\":\"基于过去6小时磁盘增长速率，预测磁盘填满所需时间（小时）。红<24h 橙24-72h 绿>72h。0=已满/无数据。\",\"gridPos\":{\"h\":6,\"w\":10,\"x\":0,\"y\":25},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\"},\"targets\":[{\"expr\":\"predict_linear(node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}[6h], 3600) / node_filesystem_size_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"} * (node_filesystem_size_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"} / node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}) / 3600\",\"legendFormat\":\"{{server_name}} /\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"h\",\"min\":0,\"max\":720,\"decimals\":1,\"thresholds\":{\"mode\":\"absolute\",\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"orange\",\"value\":24},{\"color\":\"green\",\"value\":72}]},\"color\":{\"mode\":\"thresholds\"}}}},{\"id\":32,\"title\":\"磁盘使用率明细\",\"type\":\"table\",\"gridPos\":{\"h\":6,\"w\":14,\"x\":10,\"y\":25},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"(1 - node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"} / node_filesystem_size_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}) * 100\",\"format\":\"table\",\"instant\":true,\"refId\":\"A\"},{\"expr\":\"(1 - windows_logical_disk_free_bytes{volume!~\\\"HarddiskVolume.*\\\",server_name=~\\\"$server_name\\\"} / windows_logical_disk_size_bytes{volume!~\\\"HarddiskVolume.*\\\",server_name=~\\\"$server_name\\\"}) * 100\",\"format\":\"table\",\"instant\":true,\"refId\":\"B\"}],\"transformations\":[{\"id\":\"merge\",\"options\":{}},{\"id\":\"organize\",\"options\":{\"excludeByName\":{\"Time\":true,\"__name__\":true,\"job\":true,\"managed_by\":true,\"server_id\":true,\"exporter_type\":true,\"node\":true,\"exporter_id\":true},\"renameByName\":{\"server_name\":\"服务器\",\"mountpoint\":\"挂载点\",\"volume\":\"磁盘\",\"fstype\":\"类型\",\"Value\":\"使用率%\"}}}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"decimals\":1},\"overrides\":[{\"matcher\":{\"id\":\"byName\",\"options\":\"使用率%\"},\"properties\":[{\"id\":\"custom.displayMode\",\"value\":\"color-background\"},{\"id\":\"thresholds\",\"value\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"orange\",\"value\":70},{\"color\":\"red\",\"value\":85}]}}]}]}},{\"id\":33,\"title\":\"网络流量（收/发）\",\"type\":\"timeseries\",\"gridPos\":{\"h\":6,\"w\":24,\"x\":0,\"y\":31},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(node_network_receive_bytes_total{device!~\\\"lo|docker.*|br-.*|veth.*\\\",server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} ↓{{device}}\",\"refId\":\"A\"},{\"expr\":\"rate(node_network_transmit_bytes_total{device!~\\\"lo|docker.*|br-.*|veth.*\\\",server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} ↑{{device}}\",\"refId\":\"B\"},{\"expr\":\"rate(windows_net_bytes_received_total{nic!~\\\".*Loopback.*|.*isatap.*\\\",server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} ↓{{nic}} (Win)\",\"refId\":\"C\"},{\"expr\":\"rate(windows_net_bytes_sent_total{nic!~\\\".*Loopback.*|.*isatap.*\\\",server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} ↑{{nic}} (Win)\",\"refId\":\"D\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"Bps\",\"custom\":{\"lineWidth\":2,\"fillOpacity\":6},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"},\"legend\":{\"displayMode\":\"list\",\"placement\":\"bottom\"}}},{\"id\":40,\"type\":\"row\",\"title\":\"⚫ L3 专家诊断层 — 异常检测 & 单机详情（折叠）\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":37},\"collapsed\":true,\"panels\":[{\"id\":41,\"title\":\"CPU Z-score 异常检测\",\"type\":\"timeseries\",\"description\":\"Z-score = (当前CPU - 1h均值) / 1h标准差。|Z|>3 代表统计异常（偏离正常3个标准差）。红色超标线=异常阈值。\",\"gridPos\":{\"h\":8,\"w\":12,\"x\":0,\"y\":38},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"(100 - avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100 - avg_over_time((100 - avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100)[1h:5m])) / stddev_over_time((100 - avg by(server_name)(rate(node_cpu_seconds_total{mode=\\\"idle\\\",server_name=~\\\"$server_name\\\"}[5m])) * 100)[1h:5m])\",\"legendFormat\":\"{{server_name}} Z-score\",\"refId\":\"A\"},{\"expr\":\"vector(3)\",\"legendFormat\":\"异常阈值 +3σ\",\"refId\":\"B\"},{\"expr\":\"vector(-3)\",\"legendFormat\":\"异常阈值 -3σ\",\"refId\":\"C\"}],\"fieldConfig\":{\"defaults\":{\"custom\":{\"lineWidth\":2},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"}}},{\"id\":42,\"title\":\"磁盘趋势预测（predict_linear 未来24h）\",\"type\":\"timeseries\",\"description\":\"实线=实际磁盘使用率，虚线=predict_linear 预测趋势。超过85%红线需扩容。\",\"gridPos\":{\"h\":8,\"w\":12,\"x\":12,\"y\":38},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"(1 - node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"} / node_filesystem_size_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}} 当前使用率\",\"refId\":\"A\"},{\"expr\":\"(1 - (node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"} + predict_linear(node_filesystem_avail_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}[6h], 86400)) / node_filesystem_size_bytes{fstype!~\\\"tmpfs|overlay|squashfs\\\",server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"{{server_name}} 预测(+24h)\",\"refId\":\"B\"},{\"expr\":\"vector(85)\",\"legendFormat\":\"警戒线 85%\",\"refId\":\"C\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"custom\":{\"lineWidth\":2},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"}}}]}]}";

    /** 服务健康总览：所有 Exporter 在线状态卡片 + 历史趋势 + 明细表 */
    private static final String SERVICE_HEALTH_JSON =
            "{\"uid\":\"ops-service-health\",\"title\":\"🔌 Sentinel — 服务健康 & SLO\",\"tags\":[\"ops-monitor\",\"sentinel\",\"slo\"],\"timezone\":\"browser\",\"refresh\":\"15s\",\"time\":{\"from\":\"now-1h\",\"to\":\"now\"},\"schemaVersion\":39,\"version\":7,\"templating\":{\"list\":[{\"name\":\"server_name\",\"type\":\"query\",\"label\":\"🖥 服务器\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"query\":\"label_values(up{managed_by=\\\"ops-monitor\\\"},server_name)\",\"refresh\":2,\"includeAll\":true,\"allValue\":\".*\",\"multi\":true},{\"name\":\"exporter_type\",\"type\":\"query\",\"label\":\"🔌 类型\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"query\":\"label_values(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"},exporter_type)\",\"refresh\":2,\"includeAll\":true,\"allValue\":\".*\",\"multi\":true}]},\"panels\":[{\"id\":1,\"type\":\"row\",\"title\":\"🔥 L1 决策层 — SLO & Error Budget\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":0},\"collapsed\":false},{\"id\":2,\"title\":\"SLO — 服务可用率\",\"type\":\"gauge\",\"description\":\"服务水平目标：所有被管理 Exporter 的可用率。目标 ≥ 99.9%（每月允许宕机 43 分钟）。低于 99% 触发 P0。\",\"gridPos\":{\"h\":6,\"w\":6,\"x\":0,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\"},\"targets\":[{\"expr\":\"count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}) * 100\",\"legendFormat\":\"SLO %\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":95,\"max\":100,\"decimals\":2,\"thresholds\":{\"mode\":\"absolute\",\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"orange\",\"value\":99},{\"color\":\"green\",\"value\":99.9}]},\"color\":{\"mode\":\"thresholds\"}}}},{\"id\":3,\"title\":\"Error Budget — 月度剩余\",\"type\":\"gauge\",\"description\":\"月度错误预算剩余量。目标 SLO=99.9%，月度允许宕机 43 分钟。当前窗口内消耗情况。绿>50% 橙10-50% 红<10%。负值=预算已超支\",\"gridPos\":{\"h\":6,\"w\":6,\"x\":6,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\"},\"targets\":[{\"expr\":\"clamp_min((1 - (1 - count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"})) / (1 - 0.999)) * 100, -100)\",\"legendFormat\":\"预算剩余 %\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":-100,\"max\":100,\"decimals\":1,\"thresholds\":{\"mode\":\"absolute\",\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"orange\",\"value\":10},{\"color\":\"green\",\"value\":50}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"special\",\"options\":{\"match\":\"null+nan\",\"result\":{\"text\":\"N/A (无服务)\",\"color\":\"blue\"}}}]}}},{\"id\":4,\"title\":\"P0 — 服务不可用\",\"type\":\"stat\",\"description\":\"P0：有 Exporter 完全离线（up=0），需立即响应\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":12,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"} == 0) or vector(0)\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"red\",\"value\":1}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"0 ✓\",\"color\":\"green\"}}}]}}},{\"id\":5,\"title\":\"P1 — 严重降级\",\"type\":\"stat\",\"description\":\"P1：Exporter 离线率 > 20%，服务严重降级\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":16,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"(1 - count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"})) > 0.2 or vector(0)\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"orange\",\"value\":0.001}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"正常 ✓\",\"color\":\"green\"}}},{\"type\":\"range\",\"options\":{\"from\":0.001,\"to\":1,\"result\":{\"text\":\"P1 告警!\",\"color\":\"orange\"}}}]}}},{\"id\":6,\"title\":\"P2 — 性能问题\",\"type\":\"stat\",\"description\":\"P2：离线率 5-20%，轻微降级，需关注\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":20,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"((1 - count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"})) > 0.05 and (1 - count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"})) <= 0.2) or vector(0)\",\"instant\":true,\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"yellow\",\"value\":0.001}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"正常 ✓\",\"color\":\"green\"}}},{\"type\":\"range\",\"options\":{\"from\":0.001,\"to\":1,\"result\":{\"text\":\"P2 告警!\",\"color\":\"yellow\"}}}]}}},{\"id\":7,\"title\":\"服务在线数 / 总数\",\"type\":\"stat\",\"gridPos\":{\"h\":3,\"w\":4,\"x\":12,\"y\":4},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"colorMode\":\"background\"},\"targets\":[{\"expr\":\"count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"} == 1) or vector(0)\",\"legendFormat\":\"在线\",\"instant\":true,\"refId\":\"A\"},{\"expr\":\"count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}) or vector(0)\",\"legendFormat\":\"总计\",\"instant\":true,\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"fixed\",\"fixedColor\":\"blue\"}}}},{\"id\":8,\"title\":\"SLO 可用率趋势 & Burn Rate\",\"type\":\"timeseries\",\"description\":\"绿线：实时可用率。红线：低于 SLO=99% 警戒线。趋势下滑意味着 Error Budget 正在被消耗。\",\"gridPos\":{\"h\":6,\"w\":8,\"x\":16,\"y\":1},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 1) / count(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"}) * 100\",\"legendFormat\":\"实时 SLO %\",\"refId\":\"A\"},{\"expr\":\"vector(99)\",\"legendFormat\":\"SLO 目标 99%\",\"refId\":\"B\"},{\"expr\":\"vector(99.9)\",\"legendFormat\":\"SLO 目标 99.9%\",\"refId\":\"C\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":90,\"max\":100,\"custom\":{\"lineWidth\":2,\"fillOpacity\":10},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"},\"legend\":{\"displayMode\":\"list\",\"placement\":\"bottom\"}}},{\"id\":10,\"type\":\"row\",\"title\":\"🟦 L2 运维值班层 — 服务健康蜂窝图\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":7},\"collapsed\":false},{\"id\":11,\"title\":\"服务状态蜂窝图（绿=在线 红=离线）\",\"type\":\"stat\",\"description\":\"每个格子代表一个监控服务实例。绿色=在线，红色=离线。快速定位故障服务。\",\"gridPos\":{\"h\":8,\"w\":14,\"x\":0,\"y\":8},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\",\"textMode\":\"name\",\"colorMode\":\"background\",\"graphMode\":\"none\",\"justifyMode\":\"auto\"},\"targets\":[{\"expr\":\"up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}\",\"legendFormat\":\"{{server_name}}\\n{{exporter_type}}\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"mappings\":[{\"type\":\"value\",\"options\":{\"1\":{\"text\":\"✓ 在线\",\"color\":\"green\"}}},{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"✗ 离线\",\"color\":\"red\"}}}],\"thresholds\":{\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"green\",\"value\":1}]},\"color\":{\"mode\":\"thresholds\"}}}},{\"id\":12,\"title\":\"服务类型分布\",\"type\":\"piechart\",\"description\":\"按 exporter_type 分组统计各类监控服务数量\",\"gridPos\":{\"h\":4,\"w\":5,\"x\":14,\"y\":8},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"pieType\":\"donut\",\"legend\":{\"displayMode\":\"table\",\"placement\":\"right\",\"values\":[\"value\",\"percent\"]}},\"targets\":[{\"expr\":\"count by(exporter_type)(up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"})\",\"legendFormat\":\"{{exporter_type}}\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"}}}},{\"id\":13,\"title\":\"离线服务 — 按影响时长排序\",\"type\":\"table\",\"description\":\"Impact Score = 离线持续时长（秒）。优先处理离线时间最长的服务。\",\"gridPos\":{\"h\":4,\"w\":5,\"x\":14,\"y\":12},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"sum_over_time((up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\"} == 0)[1h:1m])\",\"format\":\"table\",\"instant\":true,\"refId\":\"A\"}],\"transformations\":[{\"id\":\"sortBy\",\"options\":{\"fields\":[{\"desc\":true,\"displayName\":\"Value\"}]}}],\"fieldConfig\":{\"defaults\":{\"unit\":\"short\"},\"overrides\":[{\"matcher\":{\"id\":\"byName\",\"options\":\"Value\"},\"properties\":[{\"id\":\"displayName\",\"value\":\"离线累计(分钟)\"},{\"id\":\"custom.displayMode\",\"value\":\"color-background\"},{\"id\":\"thresholds\",\"value\":{\"steps\":[{\"color\":\"yellow\",\"value\":null},{\"color\":\"red\",\"value\":10}]}}]}]}},{\"id\":20,\"type\":\"row\",\"title\":\"📉 服务上下线历史 & 稳定性趋势\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":16},\"collapsed\":false},{\"id\":21,\"title\":\"服务上下线历史（1=在线 0=离线）\",\"type\":\"timeseries\",\"description\":\"过去1小时各服务在线状态变化。突然跌落到0的线段即为故障时间段。\",\"gridPos\":{\"h\":8,\"w\":24,\"x\":0,\"y\":17},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}\",\"legendFormat\":\"{{server_name}} / {{exporter_type}}\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"min\":0,\"max\":1,\"custom\":{\"lineWidth\":2,\"fillOpacity\":20,\"spanNulls\":false},\"mappings\":[{\"type\":\"value\",\"options\":{\"0\":{\"text\":\"离线\"},\"1\":{\"text\":\"在线\"}}}],\"thresholds\":{\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"green\",\"value\":1}]},\"color\":{\"mode\":\"palette-classic\"}}},\"options\":{\"tooltip\":{\"mode\":\"multi\"},\"legend\":{\"displayMode\":\"list\",\"placement\":\"bottom\"}}},{\"id\":30,\"type\":\"row\",\"title\":\"⚫ L3 专家诊断层 — 服务明细\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":25},\"collapsed\":true,\"panels\":[{\"id\":31,\"title\":\"服务明细表（含所有标签）\",\"type\":\"table\",\"description\":\"完整的服务列表，包含所有标签信息，用于专家级排查。\",\"gridPos\":{\"h\":10,\"w\":24,\"x\":0,\"y\":26},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"up{managed_by=\\\"ops-monitor\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}\",\"format\":\"table\",\"instant\":true,\"refId\":\"A\"}],\"transformations\":[{\"id\":\"organize\",\"options\":{\"excludeByName\":{\"Time\":true,\"__name__\":true,\"managed_by\":true},\"renameByName\":{\"server_name\":\"服务器\",\"exporter_type\":\"类型\",\"instance\":\"采集地址\",\"job\":\"Job\",\"Value\":\"状态\"}}}],\"fieldConfig\":{\"defaults\":{},\"overrides\":[{\"matcher\":{\"id\":\"byName\",\"options\":\"状态\"},\"properties\":[{\"id\":\"custom.displayMode\",\"value\":\"color-background\"},{\"id\":\"mappings\",\"value\":[{\"type\":\"value\",\"options\":{\"1\":{\"text\":\"在线 ✓\",\"color\":\"green\"},\"0\":{\"text\":\"离线 ✗\",\"color\":\"red\"}}}]}]}]}}]}]}";

    /** 数据库与中间件：Redis/MySQL/PostgreSQL/Nginx 关键运行指标 */
    private static final String MIDDLEWARE_JSON =
            "{\"uid\":\"ops-middleware\",\"title\":\"🗄️ 数据库与中间件\",\"tags\":[\"ops-monitor\"],\"timezone\":\"browser\",\"refresh\":\"30s\",\"time\":{\"from\":\"now-1h\",\"to\":\"now\"},\"templating\":{\"list\":[{\"name\":\"server_name\",\"type\":\"query\",\"label\":\"🖥 服务器\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"query\":\"label_values(up{managed_by=\\\"ops-monitor\\\",exporter_type!~\\\"node|windows\\\"},server_name)\",\"refresh\":2,\"includeAll\":true,\"allValue\":\".*\",\"multi\":true},{\"name\":\"exporter_type\",\"type\":\"query\",\"label\":\"🔌 服务类型\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"query\":\"label_values(up{managed_by=\\\"ops-monitor\\\",exporter_type!~\\\"node|windows\\\",server_name=~\\\"$server_name\\\"},exporter_type)\",\"refresh\":2,\"includeAll\":true,\"allValue\":\".*\",\"multi\":true}]},\"panels\":[{\"id\":1,\"title\":\"服务在线状态\",\"type\":\"stat\",\"description\":\"数据库/中间件服务在线情况，绿=在线 红=离线\",\"gridPos\":{\"h\":6,\"w\":24,\"x\":0,\"y\":0},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"options\":{\"reduceOptions\":{\"calcs\":[\"lastNotNull\"]},\"orientation\":\"auto\",\"textMode\":\"name\",\"colorMode\":\"background\",\"graphMode\":\"none\"},\"targets\":[{\"expr\":\"up{managed_by=\\\"ops-monitor\\\",exporter_type!~\\\"node|windows\\\",server_name=~\\\"$server_name\\\",exporter_type=~\\\"$exporter_type\\\"}\",\"legendFormat\":\"{{server_name}} / {{exporter_type}}\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"thresholds\":{\"steps\":[{\"color\":\"red\",\"value\":null},{\"color\":\"green\",\"value\":1}]},\"color\":{\"mode\":\"thresholds\"},\"mappings\":[{\"type\":\"value\",\"options\":{\"1\":{\"text\":\"在线 ✓\"},\"0\":{\"text\":\"离线 ✗\"}}}]}}},{\"id\":2,\"type\":\"row\",\"title\":\"Redis\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":6},\"collapsed\":false},{\"id\":3,\"title\":\"Redis — 已用内存\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":0,\"y\":7},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"redis_memory_used_bytes{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}}\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"bytes\",\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2,\"fillOpacity\":8}}}},{\"id\":4,\"title\":\"Redis — 命中率\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":8,\"y\":7},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(redis_keyspace_hits_total{server_name=~\\\"$server_name\\\"}[5m]) / (rate(redis_keyspace_hits_total{server_name=~\\\"$server_name\\\"}[5m]) + rate(redis_keyspace_misses_total{server_name=~\\\"$server_name\\\"}[5m])) * 100\",\"legendFormat\":\"{{server_name}} 命中率\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"percent\",\"min\":0,\"max\":100,\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2,\"fillOpacity\":8}}}},{\"id\":5,\"title\":\"Redis — 连接数 & Key数\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":16,\"y\":7},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"redis_connected_clients{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 连接数\",\"refId\":\"A\"},{\"expr\":\"redis_db_keys{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} Key数\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2}}}},{\"id\":6,\"type\":\"row\",\"title\":\"MySQL\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":14},\"collapsed\":false},{\"id\":7,\"title\":\"MySQL — QPS\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":0,\"y\":15},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(mysql_global_status_questions{server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} QPS\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"reqps\",\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2,\"fillOpacity\":8}}}},{\"id\":8,\"title\":\"MySQL — 连接数\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":8,\"y\":15},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"mysql_global_status_threads_connected{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 活跃连接\",\"refId\":\"A\"},{\"expr\":\"mysql_global_variables_max_connections{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 最大连接\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2}}}},{\"id\":9,\"title\":\"MySQL — 慢查询\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":8,\"x\":16,\"y\":15},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(mysql_global_status_slow_queries{server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} 慢查询/s\",\"refId\":\"A\"}],\"fieldConfig\":{\"defaults\":{\"unit\":\"reqps\",\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2,\"fillOpacity\":8},\"thresholds\":{\"steps\":[{\"color\":\"green\",\"value\":null},{\"color\":\"yellow\",\"value\":1},{\"color\":\"red\",\"value\":5}]}}}},{\"id\":10,\"type\":\"row\",\"title\":\"PostgreSQL\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":22},\"collapsed\":false},{\"id\":11,\"title\":\"PostgreSQL — 数据库大小 & 连接\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":0,\"y\":23},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"pg_database_size_bytes{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} {{datname}} 大小\",\"refId\":\"A\"},{\"expr\":\"pg_stat_database_numbackends{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} {{datname}} 连接\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2}}}},{\"id\":12,\"title\":\"PostgreSQL — 事务 & 锁等待\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":12,\"y\":23},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(pg_stat_database_xact_commit{server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} 提交/s\",\"refId\":\"A\"},{\"expr\":\"rate(pg_stat_database_xact_rollback{server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} 回滚/s\",\"refId\":\"B\"},{\"expr\":\"pg_locks_count{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 锁数量\",\"refId\":\"C\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2}}}},{\"id\":13,\"type\":\"row\",\"title\":\"Nginx\",\"gridPos\":{\"h\":1,\"w\":24,\"x\":0,\"y\":30},\"collapsed\":false},{\"id\":14,\"title\":\"Nginx — 请求速率 & 活跃连接\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":0,\"y\":31},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"rate(nginx_connections_handled{server_name=~\\\"$server_name\\\"}[5m])\",\"legendFormat\":\"{{server_name}} 请求/s\",\"refId\":\"A\"},{\"expr\":\"nginx_connections_active{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 活跃连接\",\"refId\":\"B\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2,\"fillOpacity\":8}}}},{\"id\":15,\"title\":\"Nginx — 连接状态分布\",\"type\":\"timeseries\",\"gridPos\":{\"h\":7,\"w\":12,\"x\":12,\"y\":31},\"datasource\":{\"type\":\"prometheus\",\"uid\":\"VictoriaMetrics\"},\"targets\":[{\"expr\":\"nginx_connections_reading{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 读\",\"refId\":\"A\"},{\"expr\":\"nginx_connections_writing{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 写\",\"refId\":\"B\"},{\"expr\":\"nginx_connections_waiting{server_name=~\\\"$server_name\\\"}\",\"legendFormat\":\"{{server_name}} 等待\",\"refId\":\"C\"}],\"fieldConfig\":{\"defaults\":{\"color\":{\"mode\":\"palette-classic\"},\"custom\":{\"lineWidth\":2}}}}],\"schemaVersion\":39,\"version\":6}";

    // ── V6 保留的文件名（cleanObsoleteDashboards 不能删除这些）──
    private static final Set<String> V6_KEEP = Set.of(
            "infra-overview.json", "service-health.json", "middleware.json", "dashboard.yml"
    );

    // ── 废弃的旧文件名（V5 及更早版本产生的，安全删除）──
    private static final String[] OBSOLETE_FILES = {
            "global-overview.json", "windows-overview.json", "agent-dashboard.json",
            "project-overview.json", "service-overview.json", "exporter-health.json",
            "system-overview.json", "overview.json", "host-dashboard.json", "node-dashboard.json"
    };

    /**
     * 生成并写入全部 V6 仪表盘文件
     *
     * 步骤：
     *   1. 先写入三个新仪表盘（确保 Grafana 始终有文件可读）
     *   2. 再清理废弃旧文件（顺序不可颠倒）
     *   3. 同时清理 server-{id}.json 专属仪表盘（V6 改用变量筛选）
     */
    public void generateAllDashboards() {
        try {
            Path dir = getDashboardDir();
            Files.createDirectories(dir);

            // v2.9 修复 (BUG-DG-OVERRIDE):
            // 原逻辑:直接用硬编码 V6 JSON 常量写入,每次启动都会覆盖用户整改的文件
            // 新逻辑:优先读取 classpath:static/dashboards/*.json(用户可维护版本),
            //       若 classpath 中不存在或解析失败,fallback 到硬编码常量(向后兼容)
            //
            // 好处:
            //   1. 用户修改 static/dashboards/*.json 后,重启自动生效
            //   2. 不必重新编译 Java 即可升级仪表盘
            //   3. 向后兼容:classpath 资源缺失时退回 V6 硬编码,绝不丢失仪表盘
            Object infraObj  = loadOrFallback("infra-overview.json",
                    INFRA_JSON, true);   // infra 需要 PromQL 修补
            Object healthObj = loadOrFallback("service-health.json",
                    SERVICE_HEALTH_JSON, false);
            Object midObj    = loadOrFallback("middleware.json",
                    MIDDLEWARE_JSON, false);

            writeJson(dir.resolve("infra-overview.json"),   infraObj);
            writeJson(dir.resolve("service-health.json"),   healthObj);
            writeJson(dir.resolve("middleware.json"),        midObj);

            log.info("[DashboardGenerator] 仪表盘已写入: 基础设施总览 / 服务健康总览 / 数据库与中间件");

            // Step 2: 清理废弃旧文件(写入成功后才执行)
            cleanObsoleteDashboards(dir);

        } catch (IOException e) {
            log.error("[DashboardGenerator] 生成失败: {}", e.getMessage(), e);
        }
    }

    /**
     * v2.9 新增:优先从 classpath 加载仪表盘 JSON,失败时退回硬编码常量
     *
     * classpath 位置约定:static/dashboards/{filename}
     * 在 Spring Boot 应用中,这对应 src/main/resources/static/dashboards/
     *
     * @param filename      仪表盘文件名(如 "infra-overview.json")
     * @param fallbackJson  硬编码的 V6 JSON 常量(classpath 缺失时使用)
     * @param applyFixes    是否需要应用 PromQL 修补(仅 infra 需要)
     * @return 解析后的 JSON 对象
     */
    private Object loadOrFallback(String filename, String fallbackJson, boolean applyFixes)
            throws IOException {
        String resourcePath = "static/dashboards/" + filename;
        com.fasterxml.jackson.databind.JsonNode node = null;
        String source = "fallback";
        try {
            org.springframework.core.io.ClassPathResource res =
                    new org.springframework.core.io.ClassPathResource(resourcePath);
            if (res.exists()) {
                try (java.io.InputStream is = res.getInputStream()) {
                    node = objectMapper.readTree(is);
                    source = "classpath:" + resourcePath;
                }
            }
        } catch (Exception e) {
            log.warn("[DashboardGenerator] 读取 classpath:{} 失败, 将使用内置常量: {}",
                    resourcePath, e.getMessage());
        }

        // fallback
        if (node == null) {
            node = objectMapper.readTree(fallbackJson);
        }

        // 仅 infra-overview 需要程序化 PromQL 修补(CPU irate / clamp_min / 磁盘 mountpoint)
        if (applyFixes) {
            node = applyInfraPromQLFixes(node);
        }

        log.info("[DashboardGenerator] {} 加载自 {}", filename, source);
        return objectMapper.treeToValue(node, Object.class);
    }

    /**
     * FIX-DASHBOARD: 递归修补 infra-overview JsonNode 中的 PromQL 表达式
     *
     * 修复内容：
     * 1. node_cpu_seconds_total 的 rate()[5m] -> irate()[2m]，避免 counter reset 出负值
     * 2. 包装 clamp_min(..., 0) 防止 CPU% 显示为负数（如 -3.7%）
     * 3. 去掉 mountpoint="/" 限制（WSL/容器环境中该挂载点不存在，导致 No data）
     *
     * 使用 Jackson ObjectNode 递归遍历修改，彻底避免多层字符串转义问题。
     * JsonNode.asText() 返回的是原始字符串值（不含 JSON 转义），replace 参数只需一层转义。
     */
    private com.fasterxml.jackson.databind.JsonNode applyInfraPromQLFixes(
            com.fasterxml.jackson.databind.JsonNode root) {
        try {
            // 深度拷贝为可修改的 ObjectNode
            com.fasterxml.jackson.databind.node.ObjectNode mutable =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                            objectMapper.writeValueAsString(root));
            patchExprs(mutable);
            return mutable;
        } catch (Exception e) {
            log.warn("[DashboardGenerator] PromQL 修补失败，使用原始 JSON: {}", e.getMessage());
            return root;
        }
    }

    /**
     * 递归遍历 JsonNode，对所有 "expr" 字段应用 PromQL 修复规则
     * asText() 返回原始字符串（无 JSON 转义），replace 参数只需普通 Java 转义
     *
     * v2.14 重构：硬编码 replace 链改为声明式规则表（PROMQL_REPLACE_RULES）
     *   + 条件包装后处理（applyConditionalWraps）。
     * 行为与 v2.13 完全一致，只是代码结构更清晰，新增规则只需加一行。
     */

    // ── 声明式 PromQL 修补规则（纯字符串替换） ──────────────────────────────────

    /** 纯字符串替换规则：search → replace，按声明顺序依次执行 */
    private record PromQLPatchRule(String description, String search, String replace) {}

    private static final java.util.List<PromQLPatchRule> PROMQL_REPLACE_RULES = java.util.List.of(
        // 规则1: rate→irate，减少 counter reset 导致的负值
        new PromQLPatchRule("CPU rate→irate",
            "rate(node_cpu_seconds_total{mode=\"idle\",server_name=~\"$server_name\"}[5m])",
            "irate(node_cpu_seconds_total{mode=\"idle\",server_name=~\"$server_name\"}[2m])"),
        // 规则3: 去掉 mountpoint="/" 限制（WSL 环境挂载点不是 /）
        new PromQLPatchRule("去 mountpoint(逗号前)",
            ",mountpoint=\"/\"", ""),
        new PromQLPatchRule("去 mountpoint(逗号后)",
            "mountpoint=\"/\",", ""),
        // 清理残留双逗号（去掉 mountpoint 后可能产生 ,, 语法错误）
        new PromQLPatchRule("清理双逗号",
            ",,", ",")
    );

    // ── 递归遍历 + 规则应用 ──────────────────────────────────────────────────

    private void patchExprs(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj =
                    (com.fasterxml.jackson.databind.node.ObjectNode) node;
            if (obj.has("expr")) {
                String expr = obj.get("expr").asText();
                // 第一步：声明式纯字符串替换
                for (PromQLPatchRule rule : PROMQL_REPLACE_RULES) {
                    String before = expr;
                    expr = expr.replace(rule.search(), rule.replace());
                    if (!before.equals(expr)) {
                        log.debug("[patchExprs] 应用规则: {}", rule.description());
                    }
                }
                // 第二步：条件包装后处理（含 contains/startsWith 判断，无法简化为纯替换）
                expr = applyConditionalWraps(expr);
                obj.put("expr", expr);
            }
            obj.fields().forEachRemaining(entry -> patchExprs(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::patchExprs);
        }
    }

    /**
     * 条件包装后处理：根据 expr 内容特征决定是否包装 clamp_min / max by
     * 这些规则包含 contains/startsWith 逻辑，无法简化为纯 search→replace。
     */
    private String applyConditionalWraps(String expr) {
        // 规则2: CPU irate 表达式包装 clamp_min 兜底（防负值 -3.7%）
        if (expr.contains("irate(node_cpu_seconds_total")
                && !expr.contains("clamp_min")
                && !expr.contains("avg_over_time")) {
            expr = "clamp_min(" + expr + ", 0)";
        }
        // 规则4a: 磁盘预测复杂旧版(含 size_bytes + [6h],3600) → 简化版带 max by 聚合
        if (expr.contains("predict_linear(node_filesystem_avail_bytes")
                && expr.contains("node_filesystem_size_bytes")
                && expr.contains("[6h], 3600)")) {
            expr = "clamp_min(max by(server_name) (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\","
                    + "server_name=~\"$server_name\"} / -predict_linear("
                    + "node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\","
                    + "server_name=~\"$server_name\"}[6h], 1) / 3600), 0)";
        }
        // 规则4b: 已简化版（无 size_bytes，但缺 max by）→ 补聚合
        if (expr.contains("predict_linear(node_filesystem_avail_bytes")
                && !expr.contains("node_filesystem_size_bytes")
                && !expr.contains("max by")) {
            expr = "clamp_min(max by(server_name) (" + expr + "), 0)";
        }
        // 规则4c: 当前使用率缺 max by → 防 WSL 多挂载点 50+ 条腿
        if (expr.startsWith("(1 - node_filesystem_avail_bytes")
                && expr.contains("/ node_filesystem_size_bytes")
                && expr.contains(") * 100")
                && !expr.contains("predict_linear")
                && !expr.contains("max by")) {
            expr = "max by(server_name) (" + expr + ")";
        }
        // 规则4d: 预测(+24h) 缺 max by → 补聚合
        if (expr.contains("predict_linear(node_filesystem_avail_bytes")
                && expr.contains("node_filesystem_size_bytes")
                && expr.contains("[6h], 86400)")
                && !expr.contains("max by")) {
            expr = "max by(server_name) (" + expr + ")";
        }
        return expr;
    }


    /**
     * 原子写入：通过 ObjectMapper 序列化保证 JSON 合法，通过临时文件保证原子性
     * 若目标文件为只读（AccessDeniedException），打印 WARN 并跳过（不影响主流程）
     */
    private void writeJson(Path target, Object data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.nio.file.AccessDeniedException ade) {
            // 目标文件被设为只读（attrib +R），跳过写入，保留现有文件
            log.warn("[DashboardGenerator] 文件只读，跳过写入（现有文件保持不变）: {} — 提示: 执行 attrib -R 取消只读属性",
                    target.getFileName());
            // 清理残留的 .tmp 文件
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /** 清理废弃旧仪表盘文件，保护 V6 当前文件不被误删 */
    private void cleanObsoleteDashboards(Path dir) {
        // 清理已知旧文件名
        for (String name : OBSOLETE_FILES) {
            if (V6_KEEP.contains(name)) continue;
            try {
                if (Files.deleteIfExists(dir.resolve(name))) {
                    log.info("[DashboardGenerator] 已删除废弃仪表盘: {}", name);
                }
            } catch (IOException e) {
                log.debug("[DashboardGenerator] 删除 {} 失败: {}", name, e.getMessage());
            }
        }
        // 清理 server-{id}.json 专属仪表盘（V6 改用变量筛选）
        try {
            Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("server-")
                            && p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.info("[DashboardGenerator] 已删除服务器专属仪表盘: {}", p.getFileName());
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.debug("[DashboardGenerator] 清理 server-*.json 失败: {}", e.getMessage());
        }
    }

    private Path getDashboardDir() {
        return Paths.get(properties.getCompose().getWorkDir(),
                "grafana", "provisioning", "dashboards");
    }
}