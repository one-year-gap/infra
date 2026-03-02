package com.myorg.config.monitoring;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

/**
 * Pinpoint APM 설정값
 */
public record PinpointConfig(
                String pinpointRepoUrl,
                String pinpointRepoDir,
                String pinpointVersion,
                String pinpointGitRef,
                int pinpointWebPort,
                int pinpointLocalForwardPort) {
        public static PinpointConfig fromEnv() {
                return new PinpointConfig(
                                AppConfig.getValueOrDefault(EnvKey.MONITORING_PINPOINT_REPO_URL),
                                AppConfig.getValueOrDefault(EnvKey.MONITORING_PINPOINT_REPO_DIR),
                                AppConfig.getValueOrDefault(EnvKey.MONITORING_PINPOINT_VERSION),
                                AppConfig.getValueOrDefault(EnvKey.MONITORING_PINPOINT_GIT_REF),
                                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_PINPOINT_WEB_PORT)),
                                Integer.parseInt(AppConfig
                                                .getValueOrDefault(EnvKey.MONITORING_PINPOINT_LOCAL_FORWARD_PORT)));
        }

        // SSM Pinpoint 포트포워딩 파라미터 JSON
        public String ssmPortForwardParametersJson(String ssmDocument) {
                return String.format("{\"portNumber\":[\"%d\"],\"localPortNumber\":[\"%d\"]}", pinpointWebPort,
                                pinpointLocalForwardPort);
        }
}
