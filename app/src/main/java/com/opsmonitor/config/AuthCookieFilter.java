package com.opsmonitor.config;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * P0-2 fix: 登录成功后自动设置 httpOnly Cookie
 *
 * 问题：前端 login-inject.js 注释称"后端会 Set-Cookie"，但实际上
 *       AuthController 的登录响应中没有 Set-Cookie 头，Token 只存在 JSON body 中。
 *       前端被迫将 Token 存入 localStorage（XSS 可窃取）。
 *
 * 修复方案：
 *   用 Servlet Filter 拦截 /api/auth/login 的响应，从 JSON body 中提取 token 值，
 *   通过 Set-Cookie 设置 httpOnly Cookie。不修改已有的 AuthController 代码。
 *
 * 同时处理 /api/auth/logout：清除 Cookie。
 */
@Slf4j
@Component
public class AuthCookieFilter implements Filter {

    /** Cookie 有效期：7 天（与 Token TTL 一致） */
    private static final int COOKIE_MAX_AGE = 7 * 24 * 60 * 60;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path   = request.getRequestURI();
        String method = request.getMethod();

        // 只拦截 POST /api/auth/login 和 POST /api/auth/logout
        if ("POST".equalsIgnoreCase(method) && "/api/auth/login".equals(path)) {
            handleLogin(request, response, chain);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/auth/logout".equals(path)) {
            handleLogout(request, response, chain);
            return;
        }

        chain.doFilter(req, res);
    }

    private void handleLogin(HttpServletRequest request, HttpServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
        // 包装 response 以捕获 body 内容
        CaptureResponseWrapper wrapper = new CaptureResponseWrapper(response);
        chain.doFilter(request, wrapper);

        String body = wrapper.getCapturedBody();
        int status  = wrapper.getStatus();

        // 从 JSON body 中提取 token（格式：{"code":200,"data":{"token":"xxx",...},...}）
        if (status == 200 && body != null && body.contains("\"token\"")) {
            String token = extractTokenFromJson(body);
            if (token != null && !token.isBlank()) {
                Cookie cookie = new Cookie("ops_token", token);
                cookie.setHttpOnly(true);   // JS 无法读取
                cookie.setPath("/");
                cookie.setMaxAge(COOKIE_MAX_AGE);
                // SameSite=Strict 通过 Set-Cookie header 手动设置（Servlet API 不直接支持）
                response.addHeader("Set-Cookie",
                        "ops_token=" + token
                                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + COOKIE_MAX_AGE);
                log.debug("[AuthCookieFilter] 登录成功，已设置 httpOnly Cookie");
            }
        }

        // 写出原始 body 给客户端
        response.setContentType(wrapper.getContentType());
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(body != null ? body.getBytes("UTF-8") : new byte[0]);
    }

    private void handleLogout(HttpServletRequest request, HttpServletResponse response,
                              FilterChain chain) throws IOException, ServletException {
        chain.doFilter(request, response);
        // 清除 Cookie
        response.addHeader("Set-Cookie",
                "ops_token=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        log.debug("[AuthCookieFilter] 登出，已清除 Cookie");
    }

    /**
     * 从 JSON 字符串中提取 token 值（简单字符串查找，避免引入新依赖）
     * 匹配模式："token":"xxxxxxxx"
     */
    private String extractTokenFromJson(String json) {
        int idx = json.indexOf("\"token\"");
        if (idx < 0) return null;
        // 跳过 "token":"
        int start = json.indexOf('"', idx + 7);
        if (start < 0) return null;
        start++; // 跳过开始引号
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * 内部 Response 包装器：捕获 response body 以便提取 token
     */
    private static class CaptureResponseWrapper extends jakarta.servlet.http.HttpServletResponseWrapper {
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private jakarta.servlet.ServletOutputStream outputStream;
        private PrintWriter writer;

        public CaptureResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                outputStream = new jakarta.servlet.ServletOutputStream() {
                    @Override public void write(int b) { capture.write(b); }
                    @Override public void write(byte[] b, int off, int len) { capture.write(b, off, len); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(jakarta.servlet.WriteListener l) {}
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new java.io.OutputStreamWriter(capture, "UTF-8"));
            }
            return writer;
        }

        public String getCapturedBody() {
            if (writer != null) writer.flush();
            return capture.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}