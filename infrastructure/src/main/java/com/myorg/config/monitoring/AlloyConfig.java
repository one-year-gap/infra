package com.myorg.config.monitoring;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

public record AlloyConfig(
        String alloyContainerName,
        String alloyImage,
        String alloyEcsLogGroups,
        String alloyLogEnv
) {
    public static AlloyConfig fromEnv() {
        return new AlloyConfig(
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ALLOY_CONTAINER_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ALLOY_IMAGE),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ALLOY_ECS_LOG_GROUPS),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ALLOY_LOG_ENV)
        );
    }
}
