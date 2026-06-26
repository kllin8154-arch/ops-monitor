package com.opsmonitor.controller;

import com.opsmonitor.model.ApiResponse;
import com.opsmonitor.model.User;
import com.opsmonitor.service.HealthReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * v2.20: 健康报告 API
 */
@Slf4j
@RestController
@RequestMapping("/api/health-report")
@RequiredArgsConstructor
public class HealthController extends BaseController {

    private final HealthReportService healthReportService;

    @GetMapping
    public ApiResponse<Map<String, Object>> getLatestReport(HttpServletRequest request) {
        User op = requireLogin(request);
        if (op == null) return lastError();
        Map<String, Object> report = healthReportService.getLatestReport();
        if (report == null) return ApiResponse.error(404, "暂无健康报告，请先手动生成或等待定时任务");
        return ApiResponse.ok(report);
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> listReports(HttpServletRequest request) {
        User op = requireLogin(request);
        if (op == null) return lastError();
        return ApiResponse.ok(healthReportService.listReports());
    }

    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generateReport(HttpServletRequest request) {
        User op = requireOps(request);
        if (op == null) return lastError();
        Map<String, Object> report = healthReportService.generateAndSave();
        return ApiResponse.ok("健康报告已生成", report);
    }
}
