package com.opsmonitor.service;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置文件自动备份服务 (9H-5 + Hotfix-1)
 *
 * Hotfix-1: ReentrantLock 防止启动时间接近凌晨2点导致双重备份
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigBackupService {

    private final OpsMonitorProperties properties;

    private static final int KEEP_DAYS = 7;

    /** Hotfix-1: 并发保护锁 */
    private final ReentrantLock backupLock = new ReentrantLock();

    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledBackup() {
        backup();
    }

    /**
     * 手动触发备份（Hotfix-1: tryLock 防止并发）
     */
    public String backup() {
        if (!backupLock.tryLock()) {
            log.info("[ConfigBackup] 备份正在进行中，跳过");
            return "备份正在进行中";
        }
        try {
            return doBackup();
        } finally {
            backupLock.unlock();
        }
    }

    private String doBackup() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path backupDir = Paths.get(properties.getCompose().getWorkDir(), "..", "backup", date).normalize();

        try {
            Files.createDirectories(backupDir);
            int copied = 0;

            String workDir = properties.getCompose().getWorkDir();
            String dataDir = Paths.get(workDir, "..", "data").normalize().toString();

            // 备份 docker 配置文件
            copied += copyIfExists(Paths.get(workDir, "prometheus.yml"), backupDir.resolve("prometheus.yml"));
            copied += copyIfExists(Paths.get(workDir, "alert.rules.yml"), backupDir.resolve("alert.rules.yml"));
            copied += copyIfExists(Paths.get(workDir, "alertmanager.yml"), backupDir.resolve("alertmanager.yml"));
            copied += copyIfExists(Paths.get(workDir, "docker-compose.yml"), backupDir.resolve("docker-compose.yml"));

            // 备份 targets 目录
            Path targetsDir = Paths.get(workDir, "targets");
            if (Files.isDirectory(targetsDir)) {
                Path backupTargets = backupDir.resolve("targets");
                Files.createDirectories(backupTargets);
                try (var files = Files.list(targetsDir)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> {
                                try {
                                    Files.copy(p, backupTargets.resolve(p.getFileName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException ignored) {}
                            });
                }
                copied++;
            }

            // 备份 data 文件
            copied += copyIfExists(Paths.get(dataDir, "exporters.json"), backupDir.resolve("exporters.json"));
            copied += copyIfExists(Paths.get(dataDir, "servers.json"), backupDir.resolve("servers.json"));
            copied += copyIfExists(Paths.get(dataDir, "assets.json"), backupDir.resolve("assets.json"));
            copied += copyIfExists(Paths.get(dataDir, "users.json"), backupDir.resolve("users.json"));

            // 清理过期备份
            cleanOldBackups();

            log.info("[ConfigBackup] 备份完成: {} ({}个文件)", backupDir, copied);
            return backupDir.toString();
        } catch (IOException e) {
            log.error("[ConfigBackup] 备份失败: {}", e.getMessage());
            return "备份失败: " + e.getMessage();
        }
    }

    private int copyIfExists(Path source, Path target) {
        try {
            if (Files.exists(source) && Files.isRegularFile(source)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return 1;
            }
        } catch (IOException e) {
            log.debug("[ConfigBackup] 复制失败: {} → {}", source, e.getMessage());
        }
        return 0;
    }

    private void cleanOldBackups() {
        try {
            Path backupRoot = Paths.get(properties.getCompose().getWorkDir(), "..", "backup").normalize();
            if (!Files.isDirectory(backupRoot)) return;

            LocalDate cutoff = LocalDate.now().minusDays(KEEP_DAYS);
            LocalDate today = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            try (var dirs = Files.list(backupRoot)) {
                dirs.filter(Files::isDirectory)
                        .forEach(dir -> {
                            try {
                                String name = dir.getFileName().toString();
                                LocalDate dirDate = LocalDate.parse(name, fmt);
                                // Hotfix-5: 严格只删除 cutoff 之前的，绝不删除今天
                                if (dirDate.isBefore(cutoff) && !dirDate.isEqual(today)) {
                                    deleteRecursively(dir);
                                    log.info("[ConfigBackup] 已清理过期备份: {}", name);
                                }
                            } catch (Exception ignored) {
                                // 非日期命名的目录忽略
                            }
                        });
            }
        } catch (IOException e) {
            log.debug("[ConfigBackup] 清理备份异常: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                var list = entries.toList();
                for (Path entry : list) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}