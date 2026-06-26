package com.opsmonitor.config;

import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.opsmonitor.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理器（P1-1 安全修复 + v2.3 ClientAbort修复）
 *
 * 原则：详细信息写日志，返回给前端的只有通用错误消息。
 * 防止泄露内部文件路径、Docker 状态、堆栈等敏感信息。
 *
 * v2.3 修复：
 * - ClientAbortException: 浏览器主动断开连接（导航/刷新），静默忽略，不写 ERROR 日志
 * - IOException (连接中断): 同上，静默忽略，避免向已关闭的连接写响应导致二次异常
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== v2.3 新增：连接中断静默处理 ====================

    /**
     * AsyncRequestNotUsableException — Spring 6.x 关机/客户端断开时的异步流写入异常
     *
     * 根因：前端轮询（容器 stats 等长轮询接口）在服务关机瞬间被强制中断，
     * Tomcat 已关闭连接但 Jackson 仍在 flush 响应体，触发此异常。
     *
     * 修复（v2.4）：
     * - 优先于所有其他处理器捕获（必须放最前面）
     * - 静默处理，不打 ERROR 日志（这是完全正常的关机/断开行为）
     * - 不写任何响应（连接已不可用）
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("[AsyncAbort] 异步响应通道已关闭（客户端断开或服务关机）: {}", e.getMessage());
        // 静默：不写响应，不打 ERROR
    }

    /**
     * ClientAbortException — 浏览器刷新/导航离开导致连接中断
     * 这是完全正常的行为，不需要记录 ERROR，也不能写响应（连接已断）
     * 必须放在最前面，优先于 IOException 和 Exception 处理
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException e) {
        // 静默处理：连接已断开，无需记录错误，无需返回响应
        log.debug("[ClientAbort] 客户端主动断开连接（浏览器刷新/导航）: {}", e.getMessage());
        // 注意：此处不能返回任何响应（连接已关闭）
    }

    /**
     * IOException — 包含静态资源（CSS/JS）传输过程中的连接中断
     * 判断是否为连接中断，是则静默，否则降级处理
     */
    @ExceptionHandler(IOException.class)
    public ApiResponse<String> handleIOException(IOException e) {
        String msg = e.getMessage();
        // 连接中断特征（Windows / Linux 常见消息）
        if (isConnectionReset(msg)) {
            log.debug("[IO] 传输中断（客户端断开）: {}", msg);
            return null; // 连接已断，不写响应
        }
        // ClosedChannelException 是关机瞬间 NIO 通道关闭，属于正常现象
        if (e.getCause() instanceof java.nio.channels.ClosedChannelException
                || (msg != null && msg.contains("ClosedChannelException"))) {
            log.debug("[IO] NIO 通道已关闭（服务关机）: {}", msg);
            return null;
        }
        log.error("IO 异常: {}", msg, e);
        return ApiResponse.error("IO 操作失败，请重试");
    }

    // ==================== 原有处理器（保持不变）====================

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<String> handleNotFound(NotFoundException e) {
        log.warn("Docker 资源未找到: {}", e.getMessage());
        return ApiResponse.error(404, "请求的资源不存在");
    }

    @ExceptionHandler(NotModifiedException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<String> handleNotModified(NotModifiedException e) {
        log.info("容器状态未改变: {}", e.getMessage());
        return ApiResponse.ok("操作完成（状态未改变）");
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<String> handleConflict(ConflictException e) {
        log.warn("Docker 操作冲突: {}", e.getMessage());
        return ApiResponse.error(409, "操作冲突，请稍后重试");
    }

    @ExceptionHandler(DockerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<String> handleDockerException(DockerException e) {
        log.error("Docker 操作异常: {}", e.getMessage());
        return ApiResponse.error("Docker 操作失败，请检查容器状态");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<String> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<String> handleSecurity(SecurityException e) {
        log.warn("权限拒绝: {}", e.getMessage());
        return ApiResponse.error(403, "权限不足");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<String> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.error(404, "资源未找到");
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<String> handleRuntimeException(RuntimeException e) {
        // ClientAbortException 是 RuntimeException 子类，但已被上面优先捕获
        if (e.getCause() instanceof ClientAbortException || isConnectionReset(e.getMessage())) {
            log.debug("[Runtime] 客户端断开: {}", e.getMessage());
            return null;
        }
        log.error("运行时异常: {}", e.getMessage(), e);
        return ApiResponse.error("操作失败，请联系管理员");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<String> handleException(Exception e) {
        // 兜底：再次检查是否为连接中断
        if (e instanceof ClientAbortException || isConnectionReset(e.getMessage())) {
            log.debug("[Exception] 客户端断开（兜底捕获）: {}", e.getMessage());
            return null;
        }
        log.error("系统异常: {}", e.getMessage(), e);
        return ApiResponse.error("系统内部错误，请联系管理员");
    }

    // ==================== 工具方法 ====================

    /**
     * 判断异常消息是否属于连接中断（跨平台）
     */
    private boolean isConnectionReset(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("connection reset")          // Linux
                || lower.contains("broken pipe")               // Linux
                || lower.contains("software caused connection abort") // Windows
                || lower.contains("中止了一个已建立的连接")     // Windows 中文
                || lower.contains("远程主机强迫关闭")           // Windows 中文
                || lower.contains("connection abort")
                || lower.contains("client disconnected");
    }
}