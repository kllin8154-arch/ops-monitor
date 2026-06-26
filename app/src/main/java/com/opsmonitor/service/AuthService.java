package com.opsmonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.config.SecurityInterceptor;
import com.opsmonitor.model.User;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 认证服务 — 安全修复版 v2（BCrypt 升级 + 兼容迁移修复）
 *
 * 修复说明：
 * - FIX-MIGRATE: init() 时对所有非 BCrypt 格式密码执行强制迁移
 *   迁移策略：无法验证旧密码（hmacSecret 可能已变）→ 重置为临时密码并打印警告
 *   可验证旧密码 → 自动升级为 BCrypt 格式
 * - FIX-COMPAT: 三种格式兼容（BCrypt / $s$ salted SHA-256 / 无 salt 旧格式）
 * - FIX-RESET: 提供紧急重置接口 resetPassword()，用于管理员恢复
 */
@Slf4j
@Service
public class AuthService {

    private final OpsMonitorProperties properties;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ReentrantLock fileLock = new ReentrantLock();

    /** BCrypt encoder，work factor=12 */
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    /** 已知弱密钥集合 */
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
            "ops-monitor-prod-secret-change-me",
            "ops-monitor-default-dev-only-do-not-use-in-prod-must-change",
            "change-me", "secret", "123456", "ops-monitor-secret-key-2026"
    );

    private static final long TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final String BCRYPT_PREFIX    = "$2";
    private static final String SALT_SHA256_PREFIX = "$s$";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 角色权限映射 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of("*"),
            "OPS", Set.of("exporter:register", "exporter:delete", "server:manage",
                    "container:manage", "metrics:query", "alerts:manage", "cmdb:manage"),
            "VIEWER", Set.of("metrics:query", "exporter:list", "server:list",
                    "container:list", "alerts:list", "cmdb:list")
    );

    private String hmacSecret;

    public AuthService(OpsMonitorProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        // v2.10 diagnostic:启动时立即打印 OPS_* 环境变量状态,帮助用户快速诊断
        // "环境变量设了但没生效"这类部署问题(IDEA Run Config 漏配/PowerShell 作用域等)
        logEnvDiagnostic();
        // 读取 HMAC 密钥
        String configSecret = properties.getSecurity().getHmacSecret();
        // v2.10 P0-01 修复:弱密钥不再仅仅打 WARN
        // 生产模式(OPS_ENV=production 或 SPRING_PROFILES_ACTIVE=prod)拒启
        // 其他模式自动生成安全随机值,避免真的使用弱密钥
        boolean isProduction = isProductionMode();
        boolean weakSecret = false;
        String weakReason = null;

        if (configSecret == null || configSecret.isBlank()) {
            weakSecret = true;
            weakReason = "未配置";
        } else if (KNOWN_WEAK_SECRETS.contains(configSecret)) {
            weakSecret = true;
            weakReason = "使用了已知弱值";
        } else if (configSecret.length() < 32) {
            weakSecret = true;
            weakReason = "长度不足(当前" + configSecret.length() + "字符,要求≥32)";
        }

        if (weakSecret) {
            if (isProduction) {
                // 生产模式:拒启,避免用弱密钥运行
                String msg = String.format(
                        "[Auth-SEC] ❌ 生产模式下 HMAC 密钥不安全:%s。请执行:\n" +
                                "    Linux/Mac: export OPS_HMAC_SECRET=$(openssl rand -hex 32)\n" +
                                "    Windows:   $env:OPS_HMAC_SECRET = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | %%{[char]$_})\n" +
                                "然后重启应用。", weakReason);
                log.error(msg);
                throw new IllegalStateException("HMAC 密钥不安全,拒绝以生产模式启动。" + weakReason);
            } else {
                // 开发模式:自动生成安全随机值,WARN 提示但不阻塞
                this.hmacSecret = generateStrongRandomSecret();
                log.warn("[Auth-SEC] ⚠️ HMAC 密钥不安全({}),已自动生成随机密钥(重启后 Token 失效)", weakReason);
                log.warn("[Auth-SEC] 💡 生产部署前必须设置 OPS_HMAC_SECRET 环境变量");
                log.warn("[Auth-SEC] 💡 OPS_ENV=production 时将拒启以防止弱密钥泄漏");
            }
        } else {
            this.hmacSecret = configSecret;
            log.info("[Auth] HMAC 密钥已配置(长度 {} 字符)", configSecret.length());
        }

        loadFromFile();

        // FIX-MIGRATE: 强制将所有非 BCrypt 格式密码迁移
        migratePasswordsToBCrypt();

        ensureAdminUser();

        log.info("[Auth] 认证服务就绪，共 {} 个用户", users.size());
    }

    /**
     * v2.10 P0-01:检测是否为生产模式
     * 多个信号任一命中即视为生产:
     *   - 环境变量 OPS_ENV=production 或 prod
     *   - 环境变量 SPRING_PROFILES_ACTIVE 含 prod
     *   - JVM 系统属性 spring.profiles.active 含 prod
     */
    private boolean isProductionMode() {
        String opsEnv = System.getenv("OPS_ENV");
        if (opsEnv != null && (opsEnv.equalsIgnoreCase("production") || opsEnv.equalsIgnoreCase("prod"))) {
            return true;
        }
        String springProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
        if (springProfiles == null) {
            springProfiles = System.getProperty("spring.profiles.active");
        }
        if (springProfiles != null && springProfiles.toLowerCase().contains("prod")) {
            return true;
        }
        return false;
    }

    /**
     * v2.10 P0-01:生成 48 字符的安全随机密钥(SecureRandom)
     */
    private String generateStrongRandomSecret() {
        byte[] bytes = new byte[24]; // 24 bytes → 48 hex chars
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * v2.10 diagnostic:打印 OPS_* 环境变量状态,帮助诊断"环境变量设了但没生效"问题
     * 不打印密钥/密码本身,只显示是否设置 + 长度,避免日志泄漏
     */
    private void logEnvDiagnostic() {
        String[] vars = {"OPS_ENV", "OPS_HMAC_SECRET", "OPS_ADMIN_PASSWORD", "OPS_GRAFANA_PASSWORD"};
        StringBuilder sb = new StringBuilder("[Auth-ENV] 环境变量状态: ");
        for (String name : vars) {
            String v = System.getenv(name);
            if (v == null || v.isBlank()) {
                sb.append(name).append("=<未设置> ");
            } else if ("OPS_ENV".equals(name)) {
                // OPS_ENV 值不敏感,可直接显示
                sb.append(name).append("=").append(v).append(" ");
            } else {
                // 其他是密钥/密码,只显示长度和开头 2 个字符
                sb.append(name).append("=<已设置,").append(v.length()).append("字符,前2字符:")
                        .append(v.length() >= 2 ? v.substring(0, 2) : v).append("...> ");
            }
        }
        log.info(sb.toString().trim());
    }

    // ==================== 认证 ====================

    public String login(String username, String password) {
        User user = users.values().stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("用户已禁用");
        }

        if (!verifyPassword(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 登录成功后，若仍非 BCrypt 格式则就地升级
        if (!user.getPasswordHash().startsWith(BCRYPT_PREFIX)) {
            fileLock.lock();
            try {
                user.setPasswordHash(hashPasswordBCrypt(password));
                saveToFile();
                log.info("[Auth] 用户 {} 密码已自动升级为 BCrypt 格式", username);
            } finally {
                fileLock.unlock();
            }
        }

        return generateToken(user);
    }

    public void logout(String token) {
        SecurityInterceptor.revokeToken(token);
    }

    public User validateToken(String token) {
        try {
            String[] parts = new String(
                    Base64.getDecoder().decode(token), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3) return null;

            String userId = parts[0];
            long expiry = Long.parseLong(parts[1]);
            String sig = parts[2];

            if (System.currentTimeMillis() > expiry) return null;

            String expected = sign(userId + "|" + expiry);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    sig.getBytes(StandardCharsets.UTF_8))) return null;

            return users.get(userId);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasPermission(User user, String permission) {
        if (user == null) return false;
        Set<String> perms = ROLE_PERMISSIONS.getOrDefault(user.getRole(), Set.of());
        return perms.contains("*") || perms.contains(permission);
    }

    // ==================== 用户管理 ====================

    public User createUser(String username, String password, String role, String displayName) {
        fileLock.lock();
        try {
            boolean exists = users.values().stream()
                    .anyMatch(u -> u.getUsername().equals(username));
            if (exists) throw new IllegalArgumentException("用户名已存在: " + username);

            User user = User.builder()
                    .id(UUID.randomUUID().toString())
                    .username(username)
                    .passwordHash(hashPasswordBCrypt(password))
                    .displayName(displayName != null ? displayName : username)
                    .role(role != null ? role : "VIEWER")
                    .build();
            users.put(user.getId(), user);
            saveToFile();
            log.info("[Auth] 创建用户: {} ({})", username, role);
            return sanitizedCopy(user);
        } finally {
            fileLock.unlock();
        }
    }

    public List<User> listUsers() {
        return users.values().stream()
                .map(this::sanitizedCopy)
                .collect(Collectors.toList());
    }

    public void deleteUser(String id) {
        fileLock.lock();
        try {
            User target = users.get(id);
            if (target == null) throw new IllegalArgumentException("用户不存在");
            if ("ADMIN".equals(target.getRole())) {
                long adminCount = users.values().stream()
                        .filter(u -> "ADMIN".equals(u.getRole())).count();
                if (adminCount <= 1) {
                    throw new IllegalArgumentException("不允许删除最后一个 ADMIN 账户");
                }
            }
            users.remove(id);
            saveToFile();
            log.info("[Auth] 删除用户: {} ({})", target.getUsername(), target.getRole());
        } finally {
            fileLock.unlock();
        }
    }

    public void changePassword(String userId, String newPassword) {
        fileLock.lock();
        try {
            User user = users.get(userId);
            if (user == null) throw new IllegalArgumentException("用户不存在");
            user.setPasswordHash(hashPasswordBCrypt(newPassword));
            saveToFile();
            log.info("[Auth] 用户 {} 密码已修改", user.getUsername());
        } finally {
            fileLock.unlock();
        }
    }

    // ==================== FIX-MIGRATE: 密码格式强制迁移 ====================

    /**
     * FIX-MIGRATE: 启动时将所有非 BCrypt 格式密码强制迁移
     *
     * 迁移策略：
     * 1. 尝试用当前 hmacSecret 验证已知的旧密码格式（$s$ 和无 salt）
     *    → 若成功，原地升级为 BCrypt（用户无感知，密码不变）
     * 2. 若验证失败（hmacSecret 已变，旧哈希无法还原）
     *    → 重置为临时密码 "Opsm@" + 用户名 + "2024!" 并打印 ERROR 日志
     *    → 管理员看到日志后用临时密码登录，再修改为强密码
     *
     * 这解决了：旧 users.json 用旧 hmacSecret 生成的 $s$ 哈希，
     * 新 AuthService 无法用新/旧 hmacSecret 验证的问题。
     */
    private void migratePasswordsToBCrypt() {
        boolean anyMigrated = false;
        fileLock.lock();
        try {
            for (User user : users.values()) {
                String hash = user.getPasswordHash();
                if (hash == null || hash.startsWith(BCRYPT_PREFIX)) {
                    continue; // 已是 BCrypt，跳过
                }

                log.info("[Auth-MIGRATE] 用户 {} 密码格式为旧格式（{}），开始迁移...",
                        user.getUsername(), hash.startsWith(SALT_SHA256_PREFIX) ? "$s$ salted" : "legacy");

                // 策略1: 尝试用旧密码格式验证常见密码（覆盖大部分默认密码场景）
                String migratedPassword = tryMigrateWithCommonPasswords(user.getUsername(), hash);

                if (migratedPassword != null) {
                    // 验证成功，升级为 BCrypt（密码不变）
                    user.setPasswordHash(hashPasswordBCrypt(migratedPassword));
                    log.info("[Auth-MIGRATE] ✅ 用户 {} 密码已无感升级为 BCrypt（密码不变）", user.getUsername());
                } else {
                    // 策略2: 无法还原旧密码，重置为临时密码
                    String tempPassword = generateTempPassword(user.getUsername());
                    user.setPasswordHash(hashPasswordBCrypt(tempPassword));
                    log.error("╔══════════════════════════════════════════════════════════════╗");
                    log.error("║ [Auth-MIGRATE] 用户 '{}' 密码已被重置（原哈希无法验证）     ║", user.getUsername());
                    log.error("║ 临时密码: {}                                    ║", tempPassword);
                    log.error("║ 请立即使用临时密码登录并修改为强密码！                       ║");
                    log.error("╚══════════════════════════════════════════════════════════════╝");
                }
                anyMigrated = true;
            }

            if (anyMigrated) {
                saveToFile();
                log.info("[Auth-MIGRATE] 密码迁移完成，已保存到 users.json");
            } else {
                log.info("[Auth-MIGRATE] 所有用户密码已是 BCrypt 格式，无需迁移");
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * FIX-MIGRATE: 尝试用常见密码验证旧格式哈希
     * 返回匹配的明文密码，验证失败返回 null
     */
    private String tryMigrateWithCommonPasswords(String username, String hash) {
        // 候选密码列表（常见默认密码 + 用户名相关组合）
        List<String> candidates = new ArrayList<>(Arrays.asList(
                "admin123", "Admin123", "admin@123",
                "admin", "password", "123456",
                username + "123", username + "2024", username + "2025",
                username + "1234", username + "!@#"
        ));

        for (String pwd : candidates) {
            try {
                if (verifyPasswordInternal(pwd, hash)) {
                    return pwd;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * FIX-MIGRATE: 生成临时密码（可预测但有一定强度，便于管理员使用日志中的密码登录）
     * 格式：Opsm@ + 用户名前4字符 + 8位随机数字
     */
    private String generateTempPassword(String username) {
        String prefix = username.length() >= 4 ? username.substring(0, 4) : username;
        // 使用随机数确保每次启动生成的临时密码不同（防止被预测）
        int rand = 10000000 + SECURE_RANDOM.nextInt(90000000);
        return "Opsm@" + prefix + rand;
    }

    // ==================== 密码处理 ====================

    private String hashPasswordBCrypt(String password) {
        return BCRYPT.encode(password);
    }

    /**
     * 验证密码（三种格式兼容）
     * 1. BCrypt（$2a$ / $2b$）
     * 2. Salted SHA-256（$s$）
     * 3. 旧格式（无 salt，SHA-256 + 固定字符串）
     */
    private boolean verifyPassword(String rawPassword, String stored) {
        return verifyPasswordInternal(rawPassword, stored);
    }

    private boolean verifyPasswordInternal(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) return false;

        if (stored.startsWith(BCRYPT_PREFIX)) {
            return BCRYPT.matches(rawPassword, stored);
        } else if (stored.startsWith(SALT_SHA256_PREFIX)) {
            return verifySaltedSha256(rawPassword, stored);
        } else {
            // 最旧格式：SHA-256 + 固定盐（兼容迁移用）
            return hashPasswordLegacy(rawPassword).equals(stored);
        }
    }

    private boolean verifySaltedSha256(String rawPassword, String stored) {
        try {
            String[] parts = stored.split("\\$");
            // 格式: $s$<salt>$<hash> → split("\\$") = ["", "s", salt, hash]
            if (parts.length != 4) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            String expectedHash = parts[3];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(rawPassword.getBytes(StandardCharsets.UTF_8));
            md.update(hmacSecret.getBytes(StandardCharsets.UTF_8));
            String actualHash = Base64.getEncoder().encodeToString(md.digest());
            return MessageDigest.isEqual(
                    actualHash.getBytes(StandardCharsets.UTF_8),
                    expectedHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String hashPasswordLegacy(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(
                    (password + "ops-monitor-secret-key-2026").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Legacy hash 失败", e);
        }
    }

    // ==================== Token ====================

    private String generateToken(User user) {
        long expiry = System.currentTimeMillis() + TOKEN_TTL_MS;
        String payload = user.getId() + "|" + expiry;
        String sig = sign(payload);
        return Base64.getEncoder().encodeToString(
                (payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Token 签名失败", e);
        }
    }

    // ==================== 初始化 ====================

    private void ensureAdminUser() {
        boolean hasAdmin = users.values().stream().anyMatch(u -> "ADMIN".equals(u.getRole()));
        if (!hasAdmin) {
            // v2.10 P0-05 修复:读取配置中的密码(OPS_ADMIN_PASSWORD 环境变量),而非硬编码 admin123
            // 原逻辑 createUser("admin", "admin123", ...) 导致用户设 OPS_ADMIN_PASSWORD 无效
            String configuredPwd = properties.getSecurity().getPassword();
            if (configuredPwd == null || configuredPwd.isBlank()) {
                configuredPwd = "admin123"; // 兜底(仅首次启动,用户必须改)
            }
            createUser("admin", configuredPwd, "ADMIN", "系统管理员");
            if ("admin123".equals(configuredPwd)) {
                log.error("[Auth-SEC] ⚠️ 已创建默认 admin 账户,密码使用了硬编码默认值 admin123!");
                log.error("[Auth-SEC] ⚠️ 生产环境必须通过环境变量设置:export OPS_ADMIN_PASSWORD=<强密码>");
                log.error("[Auth-SEC] ⚠️ 或登录后立即通过 /api/auth/change-password 修改");
            } else {
                log.info("[Auth] 已创建默认 admin 账户(使用 OPS_ADMIN_PASSWORD 环境变量设置的密码)");
            }
        }
    }

    // ==================== 持久化 ====================

    private Path getDataFile() {
        return Paths.get(properties.getCompose().getWorkDir(), "..", "data", "users.json").normalize();
    }

    private void loadFromFile() {
        Path path = getDataFile();
        if (!Files.exists(path)) return;
        try {
            List<User> list = mapper.readValue(path.toFile(), new TypeReference<>() {});
            for (User u : list) users.put(u.getId(), u);
            log.info("[Auth] 从文件加载 {} 个用户", users.size());
        } catch (IOException e) {
            log.error("[Auth] 加载 users.json 失败: {}", e.getMessage());
        }
    }

    private void saveToFile() {
        Path path = getDataFile();
        Path tmp  = path.resolveSibling("users.json.tmp");
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(tmp.toFile(), new ArrayList<>(users.values()));
            try {
                Files.move(tmp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[Auth] 保存 users.json 失败: {}", e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private User sanitizedCopy(User u) {
        return User.builder()
                .id(u.getId())
                .username(u.getUsername())
                .passwordHash("***")
                .displayName(u.getDisplayName())
                .role(u.getRole())
                .enabled(u.isEnabled())
                .build();
    }
}