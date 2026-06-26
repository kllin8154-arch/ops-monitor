package com.opsmonitor.core;

import com.opsmonitor.config.OpsMonitorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 运行态锁管理器
 *
 * 基于文件锁（FileLock）实现：
 * - 防止同一台机器上多个 OpsMonitor 实例同时运行
 * - 防止并发 compose up / yaml 写入
 * - Spring 关闭时自动释放
 *
 * 锁文件位置：docker 工作目录/ops-monitor.lock
 */
@Slf4j
@Component
public class RuntimeLockManager {

    private final OpsMonitorProperties properties;
    private RandomAccessFile lockFileHandle;
    private FileLock fileLock;
    private File lockFile;

    public RuntimeLockManager(OpsMonitorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void acquireLock() {
        try {
            Path lockPath = Paths.get(properties.getCompose().getWorkDir(), "ops-monitor.lock");
            lockFile = lockPath.toFile();
            lockFile.getParentFile().mkdirs();

            lockFileHandle = new RandomAccessFile(lockFile, "rw");
            fileLock = lockFileHandle.getChannel().tryLock();

            if (fileLock != null) {
                // 写入 PID 信息
                lockFileHandle.setLength(0);
                lockFileHandle.writeUTF("PID=" + ProcessHandle.current().pid()
                        + " TIME=" + java.time.LocalDateTime.now());
                log.info("运行态锁已获取: {}", lockPath);
            } else {
                log.error("============================================");
                log.error("  ❌ 另一个 OpsMonitor 实例正在运行！");
                log.error("  锁文件: {}", lockPath);
                log.error("  请先停止已有实例再启动。");
                log.error("============================================");
                // 不抛异常，让系统继续但标记状态
            }
        } catch (OverlappingFileLockException e) {
            log.error("运行态锁冲突：同一 JVM 内重复获取锁");
        } catch (Exception e) {
            log.warn("运行态锁获取失败（非致命）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void releaseLock() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
                log.info("运行态锁已释放");
            }
        } catch (Exception e) {
            log.debug("释放文件锁异常: {}", e.getMessage());
        }

        try {
            if (lockFileHandle != null) {
                lockFileHandle.close();
            }
        } catch (Exception e) {
            log.debug("关闭锁文件异常: {}", e.getMessage());
        }

        // 删除锁文件
        if (lockFile != null && lockFile.exists()) {
            lockFile.delete();
        }
    }

    /**
     * 是否成功持有锁
     */
    public boolean isLockHeld() {
        return fileLock != null && fileLock.isValid();
    }
}