package com.myorg.config.monitoring;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

public record LokiConfig(
    String lokiContainerName,
    String lokiImage,
    int lokiPort,

    String lokiS3Bucket,
    String lokiS3Prefix,

    //log time 설정
    int lokiTraceDebugRetentionHours,
    int lokiWarnRetentionHours,
    int lokiErrorRetentionHours,
    int lokiFatalRetentionHours
) {
    public static LokiConfig fromEnv(){
        return new LokiConfig(
                AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_CONTAINER_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_IMAGE),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_PORT)),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_S3_BUCKET),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_S3_PREFIX),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_TRACE_DEBUG_RETENTION_HOURS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_WARN_RETENTION_HOURS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_ERROR_RETENTION_HOURS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_FATAL_RETENTION_HOURS))
        );
    }
}
