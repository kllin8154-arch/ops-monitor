package com.opsmonitor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Admin UI 路由 (10E 修复 v2)
 *
 * Spring Boot 静态资源映射规则：
 *   resources/static/admin.html → 访问路径 /admin.html（不是 /static/admin.html）
 *   resources/static/vue.global.prod.min.js → 访问路径 /vue.global.prod.min.js
 *
 * /admin → 重定向到 /admin.html
 */
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String adminPage() {
        return "redirect:/admin.html";
    }
}