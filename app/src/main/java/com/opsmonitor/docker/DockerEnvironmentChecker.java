package com.opsmonitor.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Docker 环境检查器 (P2-5: 增加 10s TTL 缓存)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerEnvironmentChecker {

    private final DockerClient dockerClient;

    /** P2-5: 缓存上次检查结果 */
    private volatile boolean lastAvailable = false;
    private volatile long lastCheckTime = 0;
    private static final long CACHE_TTL_MS = 10_000; // 10 秒

    public boolean isDockerAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CACHE_TTL_MS) {
            return lastAvailable;
        }
        try {
            dockerClient.pingCmd().exec();
            lastAvailable = true;
            lastCheckTime = now;
            return true;
        } catch (Exception e) {
            lastAvailable = false;
            lastCheckTime = now;
            log.error("Docker 连接失败: {}", e.getMessage());
            return false;
        }
    }

    public String getDockerVersion() {
        try {
            Version version = dockerClient.versionCmd().exec();
            return version.getVersion();
        } catch (Exception e) {
            log.error("获取 Docker 版本失败: {}", e.getMessage());
            return "unknown";
        }
    }

    public Info getDockerInfo() {
        try {
            return dockerClient.infoCmd().exec();
        } catch (Exception e) {
            log.error("获取 Docker 信息失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isComposeAvailable() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "docker-compose", "--version");
            } else {
                pb = new ProcessBuilder("sh", "-c", "docker compose version 2>/dev/null || docker-compose --version 2>/dev/null");
            }
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("docker-compose 可用");
                return true;
            }
        } catch (Exception e) {
            log.warn("docker-compose 检测失败: {}", e.getMessage());
        }
        log.warn("docker-compose 不可用");
        return false;
    }
}