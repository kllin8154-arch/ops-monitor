package com.opsmonitor.monitor;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * Prometheus 管理器实现
 *
 * 工程级强化：
 * - yamlLock：所有 YAML 读写操作加 synchronized，防止并发注册丢配置
 * - 备份机制：写入前先备份 .bak，reload 失败时自动回滚
 * - reload 校验：严格校验 HTTP 200，非 200 抛异常
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrometheusManagerImpl implements PrometheusManager {

    private final OpsMonitorProperties properties;

    /** YAML 文件并发锁 —— 所有对 prometheus.yml 的读写必须持有此锁 */
    private final Object yamlLock = new Object();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void addScrapeTarget(String jobName, List<String> targets, Map<String, String> labels) {
        synchronized (yamlLock) {
            try {
                Map<String, Object> config = loadConfig();
                List<Map<String, Object>> scrapeConfigs = getScrapeConfigs(config);

                // 移除同名 job（幂等）
                scrapeConfigs.removeIf(job -> jobName.equals(job.get("job_name")));

                // 构建新的 scrape_config
                Map<String, Object> newJob = new LinkedHashMap<>();
                newJob.put("job_name", jobName);

                Map<String, Object> staticConfig = new LinkedHashMap<>();
                staticConfig.put("targets", new ArrayList<>(targets));
                if (labels != null && !labels.isEmpty()) {
                    staticConfig.put("labels", new LinkedHashMap<>(labels));
                }
                newJob.put("static_configs", List.of(staticConfig));

                scrapeConfigs.add(newJob);
                saveConfigWithBackup(config);

                log.info("已添加 Prometheus scrape target: {} -> {}", jobName, targets);
            } catch (Exception e) {
                log.error("添加 scrape target 失败: {}", e.getMessage(), e);
                throw new RuntimeException("添加 scrape target 失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void removeScrapeTarget(String jobName) {
        synchronized (yamlLock) {
            try {
                Map<String, Object> config = loadConfig();
                List<Map<String, Object>> scrapeConfigs = getScrapeConfigs(config);

                boolean removed = scrapeConfigs.removeIf(job -> jobName.equals(job.get("job_name")));
                if (removed) {
                    saveConfigWithBackup(config);
                    log.info("已移除 Prometheus scrape target: {}", jobName);
                } else {
                    log.warn("Prometheus scrape target 不存在: {}", jobName);
                }
            } catch (Exception e) {
                log.error("移除 scrape target 失败: {}", e.getMessage(), e);
                throw new RuntimeException("移除 scrape target 失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<String> listScrapeJobs() {
        synchronized (yamlLock) {
            try {
                Map<String, Object> config = loadConfig();
                List<Map<String, Object>> scrapeConfigs = getScrapeConfigs(config);
                return scrapeConfigs.stream()
                        .map(job -> (String) job.get("job_name"))
                        .filter(Objects::nonNull)
                        .toList();
            } catch (Exception e) {
                log.error("获取 scrape jobs 失败: {}", e.getMessage());
                return List.of();
            }
        }
    }

    /**
     * 热加载 Prometheus 配置
     * 严格校验 HTTP 200，非 200 时回滚配置并抛异常
     */
    @Override
    public boolean reloadConfig() {
        try {
            String url = String.format("http://127.0.0.1:%d/-/reload",
                    properties.getPrometheus().getPort());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                log.info("Prometheus 配置热加载成功");
                return true;
            }

            // 非 200：回滚配置并抛异常
            String errorMsg = String.format("Prometheus 热加载失败 (HTTP %d): %s",
                    statusCode, response.body());
            log.error(errorMsg);
            rollbackConfig();
            throw new RuntimeException(errorMsg);

        } catch (RuntimeException e) {
            throw e; // 不吞掉上面抛出的
        } catch (Exception e) {
            String errorMsg = "Prometheus 热加载异常: " + e.getMessage();
            log.error(errorMsg);
            rollbackConfig();
            throw new RuntimeException(errorMsg, e);
        }
    }

    @Override
    public boolean isRunning() {
        try {
            String url = String.format("http://127.0.0.1:%d/-/ready",
                    properties.getPrometheus().getPort());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 加载 prometheus.yml（调用方必须持有 yamlLock）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() throws IOException {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            log.warn("prometheus.yml 不存在，自动创建完整配置: {}", configPath);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, generateFullPrometheusConfig(), StandardCharsets.UTF_8);
        }
        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        Yaml yaml = new Yaml();
        Map<String, Object> cfg = yaml.load(content);
        if (cfg == null) cfg = new LinkedHashMap<>();
        // Phase1修复：每次加载后确保顶层关键段落不丢失
        // addScrapeTarget 只操作 scrape_configs，不会主动丢失其他段落，
        // 但若 prometheus.yml 是被旧版本写入的残缺格式，此处补全
        ensureTopLevelSections(cfg);
        return cfg;
    }

    /**
     * Phase1修复：确保 prometheus.yml 顶层关键段落存在
     * 防止旧版残缺文件导致 rule_files / alerting / remote_write 缺失
     */
    @SuppressWarnings("unchecked")
    private void ensureTopLevelSections(Map<String, Object> config) {
        // global
        if (!config.containsKey("global")) {
            Map<String, Object> global = new LinkedHashMap<>();
            global.put("scrape_interval", "15s");
            global.put("evaluation_interval", "15s");
            config.put("global", global);
            log.info("[PrometheusManager] 补全 global 段");
        }
        // rule_files
        if (!config.containsKey("rule_files")) {
            config.put("rule_files", List.of(
                    "/etc/prometheus/alert.rules.yml",
                    "/etc/prometheus/recording_rules.yml"
            ));
            log.info("[PrometheusManager] 补全 rule_files 段（含 recording_rules）");
        }
        // alerting
        if (!config.containsKey("alerting")) {
            Map<String, Object> am = new LinkedHashMap<>();
            Map<String, Object> staticCfg = new LinkedHashMap<>();
            staticCfg.put("targets", List.of("ops-alertmanager:9093"));
            am.put("alertmanagers", List.of(Map.of("static_configs", List.of(staticCfg))));
            config.put("alerting", am);
            log.info("[PrometheusManager] 补全 alerting 段");
        }
        // remote_write — 写入 VictoriaMetrics
        if (!config.containsKey("remote_write")) {
            config.put("remote_write", List.of(Map.of("url", "http://ops-victoria:8428/api/v1/write")));
            log.info("[PrometheusManager] 补全 remote_write → VictoriaMetrics 段");
        }
        // 确保 node-exporter job 存在（本机 CPU/内存/磁盘数据来源）
        List<Map<String, Object>> scrapeConfigs = getScrapeConfigs(config);
        boolean hasNodeJob = scrapeConfigs.stream()
                .anyMatch(j -> "node-exporter".equals(j.get("job_name")));
        if (!hasNodeJob) {
            Map<String, Object> nodeJob = new LinkedHashMap<>();
            nodeJob.put("job_name", "node-exporter");
            // file_sd_configs
            Map<String, Object> fileSd = new LinkedHashMap<>();
            fileSd.put("files", List.of("/etc/prometheus/targets/node.json"));
            fileSd.put("refresh_interval", "30s");
            nodeJob.put("file_sd_configs", List.of(fileSd));
            // static_configs (本机 node-exporter)
            Map<String, Object> labels = new LinkedHashMap<>();
            labels.put("server_name", "本机");
            labels.put("server_id", "local");
            labels.put("managed_by", "ops-monitor");
            labels.put("exporter_type", "node");
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("targets", List.of("ops-node-exporter:9100"));
            sc.put("labels", labels);
            nodeJob.put("static_configs", List.of(sc));
            scrapeConfigs.add(0, nodeJob); // 放在最前面
            log.info("[PrometheusManager] 补全 node-exporter job（本机指标来源）");
        }

        // v2.9 BUG-DUAL-SCRAPE 修复：
        //   症状:Grafana 蜂窝图出现重复格子（政/nginx × 2,政/postgres × 2 等）
        //   根因:旧版 prometheus.yml 保留了通用 file_sd job(redis-exporter/mysql-exporter/nginx-exporter 等),
        //         而新版改用按 exporter_id 注册独立 job,两者同时存在导致同一 exporter 被双重抓取
        //   修复:升级启动时主动删除这些过时的通用 file_sd job
        //
        // 保留:node-exporter / windows-exporter(这两个走 file_sd 是架构设计,不能删)
        //       prometheus(内置自监控)
        //       任何 job_name 含数字-IP 格式的 exporter_id 独立 job(addScrapeTarget 产生)
        Set<String> obsoleteJobNames = Set.of(
                "redis-exporter", "mysql-exporter", "postgres-exporter",
                "nginx-exporter", "jmx-exporter", "process-exporter",
                "oracle-exporter", "kingbase-exporter", "dm-exporter",
                "geoserver-exporter"
        );
        int removed = 0;
        java.util.Iterator<Map<String, Object>> it = scrapeConfigs.iterator();
        while (it.hasNext()) {
            Map<String, Object> job = it.next();
            String name = String.valueOf(job.get("job_name"));
            if (obsoleteJobNames.contains(name)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.warn("[PrometheusManager] BUG-DUAL-SCRAPE 修复：已清理 {} 个过时通用 file_sd job(避免与 exporter_id 独立 job 重复抓取)", removed);
        }
    }

    /**
     * 生成完整的 prometheus.yml 模板
     * 包含：global / rule_files / alerting / remote_write / scrape_configs（含node-exporter）
     */
    private String generateFullPrometheusConfig() {
        return """
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/alert.rules.yml
  - /etc/prometheus/recording_rules.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['ops-alertmanager:9093']

remote_write:
  - url: http://ops-victoria:8428/api/v1/write

scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  - job_name: 'node-exporter'
    file_sd_configs:
      - files: ['/etc/prometheus/targets/node.json']
        refresh_interval: 30s
    static_configs:
      - targets: ['ops-node-exporter:9100']
        labels:
          server_name: '本机'
          server_id: 'local'
          managed_by: 'ops-monitor'
          exporter_type: 'node'

  # FIX-DUAL-SCRAPE: 以下旧通用 job 已删除
  # 原有 redis-exporter/mysql-exporter/postgres-exporter/nginx-exporter 等 file_sd job
  # 会与 addScrapeTarget() 注册的独立 job 产生双重抓取（Grafana 显示重复服务）
  # 现在统一使用 addScrapeTarget() 注册的独立 job（job_name=exporter_id）
  # 
  # node/windows/remote-node 类型保留 file_sd（这些类型不走 addScrapeTarget）
  # windows exporter 通过 addScrapeTarget 独立 job 注册（远程纯target模式）
""";
    }

    /**
     * 备份后保存（调用方必须持有 yamlLock）
     * 写入前先将当前文件复制为 .bak，reload 失败时可回滚
     */
    private void saveConfigWithBackup(Map<String, Object> config) throws IOException {
        Path configPath = getConfigPath();
        Path backupPath = Paths.get(configPath + ".bak");

        // 备份当前配置
        if (Files.exists(configPath)) {
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("prometheus.yml 已备份: {}", backupPath);
        }

        // 写入新配置
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);

        Yaml yaml = new Yaml(options);
        String content = yaml.dump(config);

        Files.writeString(configPath, content, StandardCharsets.UTF_8);
        log.debug("prometheus.yml 已保存: {}", configPath);
    }

    /**
     * 回滚到 .bak 备份
     */
    private void rollbackConfig() {
        synchronized (yamlLock) {
            try {
                Path configPath = getConfigPath();
                Path backupPath = Paths.get(configPath + ".bak");

                if (Files.exists(backupPath)) {
                    Files.copy(backupPath, configPath, StandardCopyOption.REPLACE_EXISTING);
                    log.warn("prometheus.yml 已回滚到备份版本");
                } else {
                    log.warn("无备份文件可回滚: {}", backupPath);
                }
            } catch (IOException e) {
                log.error("回滚 prometheus.yml 失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 获取 scrape_configs 可变列表（调用方必须持有 yamlLock）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getScrapeConfigs(Map<String, Object> config) {
        Object raw = config.get("scrape_configs");
        if (raw instanceof List) {
            List<Map<String, Object>> list = new ArrayList<>((List<Map<String, Object>>) raw);
            config.put("scrape_configs", list);
            return list;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        config.put("scrape_configs", list);
        return list;
    }

    private Path getConfigPath() {
        return Paths.get(properties.getPrometheus().getConfigPath());
    }
}