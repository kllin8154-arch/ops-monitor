package com.opsmonitor.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Docker 客户端配置
 * Windows: npipe:////./pipe/docker_engine (Docker Desktop 默认)
 * Linux:   unix:///var/run/docker.sock
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DockerClientConfiguration {

    private final OpsMonitorProperties properties;

    @Bean
    public DockerClientConfig dockerClientConfig() {
        String dockerHost = properties.getDocker().getHost();

        if (dockerHost == null || dockerHost.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                dockerHost = "npipe:////./pipe/docker_engine";
                log.info("检测到 Windows 系统，使用 {}", dockerHost);
            } else {
                dockerHost = "unix:///var/run/docker.sock";
                log.info("检测到 Linux 系统，使用 {}", dockerHost);
            }
        } else {
            log.info("使用配置的 Docker Host: {}", dockerHost);
        }

        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
    }

    @Bean
    public DockerHttpClient dockerHttpClient(DockerClientConfig config) {
        return new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofMillis(properties.getDocker().getConnectTimeout()))
                .responseTimeout(Duration.ofMillis(properties.getDocker().getReadTimeout()))
                .build();
    }

    @Bean
    public DockerClient dockerClient(DockerClientConfig config, DockerHttpClient httpClient) {
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
