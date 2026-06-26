package com.opsmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多服务器一体化运维监控平台 - 主启动类
 * v2.2: 新增 @EnableAsync 支持通知分发异步执行
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class OpsMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsMonitorApplication.class, args);
    }
}