package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.platform.AlertCenterService;
import com.opsmonitor.platform.NotificationChannel;
import com.opsmonitor.platform.NotificationChannelService;
import com.opsmonitor.platform.NotificationDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 通知渠道 API
 *
 * OPTIMIZED: 继承 BaseController
 *   - 删除 getUser() 私有方法（由基类提供）
 *   - 删除 AuthService 字段声明（由基类注入）
 *   - 认证样板统一使用 requireOps + lastError
 *   改造前：162 行 → 改造后：145 行（节省 17 行）
 */
@RestController
@RequestMapping("/api/v2/notifications")
@RequiredArgsConstructor
public class NotificationController extends BaseController {

    private final NotificationChannelService channelService;
    private final NotificationDispatcher dispatcher;

    private static final List<String> ALLOWED_TYPES =
            List.of("DINGTALK", "FEISHU", "WECOM", "WEBHOOK", "SLACK");

    @GetMapping("/channels")
    public ApiResponse<List<NotificationChannel>> list(HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();
        return ApiResponse.ok(channelService.listAll());
    }

    @PostMapping("/channels")
    public ApiResponse<NotificationChannel> create(@RequestBody NotificationChannel channel,
                                                   HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();

        if (channel.getName() == null || channel.getName().isBlank())
            return ApiResponse.error(400, "渠道名称不能为空");
        if (channel.getType() == null || !ALLOWED_TYPES.contains(channel.getType()))
            return ApiResponse.error(400, "不支持的渠道类型，允许: " + String.join(", ", ALLOWED_TYPES));
        if (channel.getWebhookUrl() == null || channel.getWebhookUrl().isBlank())
            return ApiResponse.error(400, "Webhook URL 不能为空");
        if (!isValidWebhookUrl(channel.getWebhookUrl()))
            return ApiResponse.error(400, "Webhook URL 格式无效，必须以 http:// 或 https:// 开头");

        return ApiResponse.ok("渠道已创建", channelService.create(channel));
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<String> update(@PathVariable String id,
                                      @RequestBody NotificationChannel channel,
                                      HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();

        if (channel.getType() != null && !ALLOWED_TYPES.contains(channel.getType()))
            return ApiResponse.error(400, "不支持的渠道类型");
        if (channel.getWebhookUrl() != null && !channel.getWebhookUrl().isBlank()
                && !isValidWebhookUrl(channel.getWebhookUrl()))
            return ApiResponse.error(400, "Webhook URL 格式无效，必须以 http:// 或 https:// 开头");

        return channelService.update(id, channel)
                ? ApiResponse.ok("已更新")
                : ApiResponse.error(404, "渠道不存在");
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<String> delete(@PathVariable String id, HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();

        return channelService.delete(id)
                ? ApiResponse.ok("已删除")
                : ApiResponse.error(404, "渠道不存在");
    }

    @PatchMapping("/channels/{id}/toggle")
    public ApiResponse<String> toggle(@PathVariable String id,
                                      @RequestParam boolean enabled,
                                      HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();

        return channelService.toggle(id, enabled)
                ? ApiResponse.ok(enabled ? "已启用" : "已禁用")
                : ApiResponse.error(404, "渠道不存在");
    }

    @PostMapping("/test/{id}")
    public ApiResponse<String> test(@PathVariable String id, HttpServletRequest request) {
        User u = requireOps(request);
        if (u == null) return lastError();

        return channelService.findById(id).map(ch -> {
            AlertCenterService.Alert testAlert = AlertCenterService.Alert.builder()
                    .alertId("test-" + System.currentTimeMillis())
                    .alertName("TestAlert")
                    .severity("warning")
                    .state("FIRING")
                    .status("firing")
                    .serverName("test-server")
                    .exporterType("node")
                    .summary("这是一条来自 OpsMonitor 的测试告警通知，请忽略。")
                    .receivedAt(System.currentTimeMillis())
                    .build();
            dispatcher.dispatch(testAlert);
            return ApiResponse.ok("测试通知已发送（异步），请在目标渠道查看");
        }).orElse(ApiResponse.error(404, "渠道不存在"));
    }

    @GetMapping("/channels/stats")
    public ApiResponse<Map<String, Object>> stats() {
        List<NotificationChannel> all = channelService.listAll();
        long enabled   = all.stream().filter(NotificationChannel::isEnabled).count();
        long totalSent = all.stream().mapToLong(NotificationChannel::getSentCount).sum();
        long totalFail = all.stream().mapToLong(NotificationChannel::getFailCount).sum();
        return ApiResponse.ok(Map.of(
                "total", all.size(),
                "enabled", enabled,
                "totalSent", totalSent,
                "totalFail", totalFail
        ));
    }

    private boolean isValidWebhookUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
