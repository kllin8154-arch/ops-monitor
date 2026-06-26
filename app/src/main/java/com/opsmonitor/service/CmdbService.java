package com.opsmonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.model.Asset;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * CMDB 资产管理服务
 *
 * 持久化：data/assets.json
 * 提供主机资产的 CRUD + 按项目/环境/服务器聚合查询
 */
@Slf4j
@Service
public class CmdbService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Asset> assets = new ConcurrentHashMap<>();
    private final ReentrantLock fileLock = new ReentrantLock();

    public CmdbService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromFile();
        log.info("CMDB 资产管理就绪，共 {} 条资产", assets.size());
    }

    // ==================== CRUD ====================

    public Asset addAsset(Asset asset) {
        fileLock.lock();
        try {
            if (asset.getId() == null || asset.getId().isBlank()) {
                asset.setId(UUID.randomUUID().toString().substring(0, 8));
            }
            asset.setCreatedAt(System.currentTimeMillis());
            asset.setUpdatedAt(System.currentTimeMillis());
            assets.put(asset.getId(), asset);
            saveToFile();
            log.info("新增资产: {} ({})", asset.getHostname(), asset.getIp());
            return asset;
        } finally {
            fileLock.unlock();
        }
    }

    public Asset updateAsset(String id, Asset update) {
        fileLock.lock();
        try {
            Asset existing = getAsset(id);
            if (update.getHostname() != null) existing.setHostname(update.getHostname());
            if (update.getIp() != null) existing.setIp(update.getIp());
            if (update.getOs() != null) existing.setOs(update.getOs());
            if (update.getEnvironment() != null) existing.setEnvironment(update.getEnvironment());
            if (update.getProject() != null) existing.setProject(update.getProject());
            if (update.getOwner() != null) existing.setOwner(update.getOwner());
            if (update.getDescription() != null) existing.setDescription(update.getDescription());
            existing.setUpdatedAt(System.currentTimeMillis());
            saveToFile();
            return existing;
        } finally {
            fileLock.unlock();
        }
    }

    public void deleteAsset(String id) {
        fileLock.lock();
        try {
            Asset removed = assets.remove(id);
            if (removed == null) throw new IllegalArgumentException("资产不存在: " + id);
            saveToFile();
            log.info("删除资产: {} ({})", removed.getHostname(), removed.getIp());
        } finally {
            fileLock.unlock();
        }
    }

    public Asset getAsset(String id) {
        Asset a = assets.get(id);
        if (a == null) throw new IllegalArgumentException("资产不存在: " + id);
        return a;
    }

    public List<Asset> listAssets() {
        return new ArrayList<>(assets.values());
    }

    // ==================== 聚合查询 ====================

    public List<Asset> listByProject(String project) {
        return assets.values().stream()
                .filter(a -> project.equals(a.getProject()))
                .collect(Collectors.toList());
    }

    public List<Asset> listByEnvironment(String env) {
        return assets.values().stream()
                .filter(a -> env.equals(a.getEnvironment()))
                .collect(Collectors.toList());
    }

    public Map<String, Long> countByProject() {
        return assets.values().stream()
                .filter(a -> a.getProject() != null)
                .collect(Collectors.groupingBy(Asset::getProject, Collectors.counting()));
    }

    public Map<String, Long> countByEnvironment() {
        return assets.values().stream()
                .filter(a -> a.getEnvironment() != null)
                .collect(Collectors.groupingBy(Asset::getEnvironment, Collectors.counting()));
    }

    // ==================== 持久化 ====================

    private Path getDataFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "assets.json").normalize();
    }

    private void loadFromFile() {
        Path path = getDataFile();
        if (!Files.exists(path)) return;
        try {
            List<Asset> list = mapper.readValue(path.toFile(), new TypeReference<>() {});
            for (Asset a : list) assets.put(a.getId(), a);
            log.info("从 assets.json 加载 {} 条资产", list.size());
        } catch (IOException e) {
            log.error("加载 assets.json 失败: {}", e.getMessage());
        }
    }

    private void saveToFile() {
        Path path = getDataFile();
        Path tmp  = path.resolveSibling("assets.json.tmp");
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), new ArrayList<>(assets.values()));
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存 assets.json 失败: {}", e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}