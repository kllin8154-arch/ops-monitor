package com.opsmonitor.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.platform.model.ManagedResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源持久化存储 (10D.1 Hotfix-1)
 *
 * 存储：data/resources/{kind}/{tenant}/{name}.json
 */
@Slf4j
@Service
public class ResourceStoreService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;

    public ResourceStoreService(OpsMonitorProperties properties) {
        this.properties = properties;
        // 修复: 不使用 findAndRegisterModules()，JDK 17 缺少 javax.xml.bind
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(ManagedResource resource) {
        try {
            Path filePath = resolveResourcePath(resource);
            Path tmp      = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.createDirectories(filePath.getParent());
            mapper.writeValue(tmp.toFile(), resource);
            try {
                Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("[ResourceStore] 已保存: {}", resource.getQualifiedName());
        } catch (IOException e) {
            log.error("[ResourceStore] 保存失败 {}: {}", resource.getQualifiedName(), e.getMessage());
        }
    }

    public void delete(String tenant, String kind, String name) {
        try {
            String t = (tenant != null) ? tenant : "default";
            Path filePath = getResourceDir().resolve(kind).resolve(t).resolve(name + ".json");
            if (Files.deleteIfExists(filePath)) {
                log.debug("[ResourceStore] 已删除: {}/{}/{}", t, kind, name);
            }
        } catch (IOException e) {
            log.error("[ResourceStore] 删除失败: {}", e.getMessage());
        }
    }

    public List<ManagedResource> loadAll() {
        List<ManagedResource> result = new ArrayList<>();
        Path root = getResourceDir();
        if (!Files.isDirectory(root)) return result;

        try (var kindDirs = Files.list(root)) {
            kindDirs.filter(Files::isDirectory).forEach(kindDir -> {
                try (var tenantDirs = Files.list(kindDir)) {
                    tenantDirs.filter(Files::isDirectory).forEach(tenantDir -> {
                        try (var files = Files.list(tenantDir)) {
                            files.filter(f -> f.toString().endsWith(".json")).forEach(file -> {
                                try {
                                    ManagedResource r = mapper.readValue(file.toFile(), ManagedResource.class);
                                    result.add(r);
                                } catch (IOException e) {
                                    log.warn("[ResourceStore] 加载失败: {}", file.getFileName());
                                }
                            });
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.error("[ResourceStore] 扫描资源目录失败: {}", e.getMessage());
        }

        log.info("[ResourceStore] 已加载 {} 个资源", result.size());
        return result;
    }

    private Path resolveResourcePath(ManagedResource resource) {
        String tenant = (resource.getTenant() != null) ? resource.getTenant() : "default";
        return getResourceDir()
                .resolve(resource.getKind())
                .resolve(tenant)
                .resolve(resource.getName() + ".json");
    }

    private Path getResourceDir() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "resources").normalize();
    }
}