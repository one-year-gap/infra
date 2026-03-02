package com.myorg.config.monitoring;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

/**
 * Grafana EC2 설치/운영에 필요한 설정값
 */
public record GrafanaConfig(
        int grafanaPort,
        int localForwardPort,

        // Grafana RPM repo
        String grafanaRepoName,
        String grafanaRepoBaseUrl,
        String grafanaRepoGpgKeyUrl,
        String grafanaPackageName,
        String grafanaServiceName,

        String grafanaAdminUser,
        String grafanaAdminPassword) {
    public static GrafanaConfig fromEnv() {
        return new GrafanaConfig(
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_PORT)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOCAL_FORWARD_PORT)),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_REPO_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_REPO_BASE_URL),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_REPO_GPG_KEY_URL),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_PACKAGE_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_GRAFANA_SERVICE_NAME),
                AppConfig.getRequiredValue(EnvKey.MONITORING_GRAFANA_ADMIN_USER.key()),
                AppConfig.getRequiredValue(EnvKey.MONITORING_GRAFANA_ADMIN_PASSWORD.key()));
    }

    // SSM Grafana 포트포워딩 파라미터 JSON
    public String ssmPortForwardParametersJson() {
        return String.format("{\"portNumber\":[\"%d\"],\"localPortNumber\":[\"%d\"]}", grafanaPort, localForwardPort);
    }
}
