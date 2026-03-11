package com.myorg.config;

import java.util.Arrays;
import java.util.List;

/**
 * on-demand workflow가 batch 실행 전에 analysis-server 상태를 점검하기 위한 설정.
 */
public record AnalysisServerReadinessConfig(
        String serviceName,
        int scaleUpDesiredCount,
        int scaleDownDesiredCount,
        String probeVpcId,
        List<String> probeSubnetIds,
        List<String> probeSecurityGroupIds,
        String readyUrl,
        String healthUrl,
        List<String> requiredReadyChecks,
        int probeTimeoutSeconds,
        int probeIntervalSeconds,
        int probeMaxAttempts,
        String probeLambdaAssetPath,
        int probeLambdaMemoryMb
) {
    public static AnalysisServerReadinessConfig fromEnv() {
        String baseUrl = AppConfig.getOptionalValueOrDefault(
                EnvKey.ON_DEMAND_ANALYSIS_SERVER_BASE_URL.key(),
                buildDefaultBaseUrl()
        );

        AnalysisServerReadinessConfig config = new AnalysisServerReadinessConfig(
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_SERVICE_NAME),
                parseNonNegativeInt(
                        AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_ANALYSIS_SERVER_SCALE_UP_DESIRED_COUNT.key(), "1"),
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_SCALE_UP_DESIRED_COUNT
                ),
                parseNonNegativeInt(
                        AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_ANALYSIS_SERVER_SCALE_DOWN_DESIRED_COUNT.key(), "0"),
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_SCALE_DOWN_DESIRED_COUNT
                ),
                AppConfig.getRequiredValue(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_VPC_ID.key()),
                parseRequiredCsv(
                        AppConfig.getOptionalValueOrDefault(
                                EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_SUBNET_IDS.key(),
                                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_SUBNET_IDS)
                        ),
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_SUBNET_IDS.key()
                ),
                parseRequiredCsv(
                        AppConfig.getRequiredValue(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_SECURITY_GROUP_IDS.key()),
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_SECURITY_GROUP_IDS.key()
                ),
                AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_READY_URL.key(),
                        baseUrl + "/ready"
                ),
                AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_HEALTH_URL.key(),
                        baseUrl + "/health"
                ),
                parseOptionalCsv(AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_REQUIRED_READY_CHECKS.key(),
                        ""
                )),
                parsePositiveInt(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_TIMEOUT_SECONDS, "5"),
                parsePositiveInt(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_INTERVAL_SECONDS, "10"),
                parsePositiveInt(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_MAX_ATTEMPTS, "18"),
                AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_LAMBDA_ASSET_PATH.key(),
                        "lambda/ready-probe"
                ),
                parsePositiveInt(EnvKey.ON_DEMAND_ANALYSIS_SERVER_PROBE_MEMORY_MB, "256")
        );

        if (config.readyUrl().isBlank() || config.healthUrl().isBlank()) {
            throw new IllegalStateException("analysis-server readiness probe URL이 비어 있습니다.");
        }

        return config;
    }

    private static String buildDefaultBaseUrl() {
        String serviceName = AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_CLOUD_MAP_NAME);
        String internalDomainName = AppConfig.getInternalDomainName();
        String port = AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_PORT);
        return "http://" + serviceName + "." + internalDomainName + ":" + port;
    }

    private static int parsePositiveInt(EnvKey key, String defaultValue) {
        int parsed = Integer.parseInt(AppConfig.getOptionalValueOrDefault(key.key(), defaultValue));
        if (parsed <= 0) {
            throw new IllegalStateException(key.key() + " 값은 1 이상이어야 합니다.");
        }
        return parsed;
    }

    private static int parseNonNegativeInt(String value, EnvKey key) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalStateException(key.key() + " 값은 0 이상이어야 합니다.");
        }
        return parsed;
    }

    private static List<String> parseRequiredCsv(String origin, String key) {
        List<String> values = parseOptionalCsv(origin);
        if (values.isEmpty()) {
            throw new IllegalStateException(key + " 값이 비어 있습니다.");
        }
        return values;
    }

    private static List<String> parseOptionalCsv(String origin) {
        if (origin == null || origin.isBlank()) {
            return List.of();
        }
        return Arrays.stream(origin.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
