package com.opsmonitor.controller;

import com.opsmonitor.config.OpsMonitorProperties;
import com.opsmonitor.docker.DockerEnvironmentChecker;
import com.opsmonitor.monitor.GrafanaManager;
import com.opsmonitor.monitor.PrometheusManager;
import com.opsmonitor.service.SystemInitializer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final SystemInitializer systemInitializer;
    private final DockerEnvironmentChecker dockerChecker;
    private final PrometheusManager prometheusManager;
    private final GrafanaManager grafanaManager;
    private final OpsMonitorProperties properties;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("initialized", systemInitializer.isInitialized());
        model.addAttribute("initError", systemInitializer.getInitError());
        model.addAttribute("dockerConnected", dockerChecker.isDockerAvailable());
        model.addAttribute("prometheusRunning", prometheusManager.isRunning());
        model.addAttribute("grafanaRunning", grafanaManager.isRunning());
        model.addAttribute("grafanaUrl", grafanaManager.getEmbedUrl());
        model.addAttribute("prometheusPort", properties.getPrometheus().getPort());
        model.addAttribute("grafanaPort", properties.getGrafana().getPort());
        return "index";
    }
}
