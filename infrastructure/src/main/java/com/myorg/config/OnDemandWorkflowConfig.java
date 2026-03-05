package com.myorg.config;

import com.myorg.config.dynamodb.DynamoDBConfig;
import software.amazon.awscdk.App;

import java.util.Arrays;
import java.util.List;

/**
 * 온디맨드 워크플로우 운영 설정.
 */
public record OnDemandWorkflowConfig(
        String stateMachineName,
        int workflowTimeoutMinutes,
        String scheduleExpression,

        int scaleUpDesiredCount,
        int scaleDownDesiredCount,

        int steadyPollSeconds,
        int steadyMaxAttempts,
        int albHealthPollSeconds,
        int albHealthMaxAttempts,

        int readyProbeTimeoutSeconds,
        int readyRetryIntervalSeconds,
        int readyMaxAttempts,
        double readyBackoffRate,
        String readyRequiredChecksCsv,

        String expectedReleaseTag,
        String fastApiImageTagPrefix,
        boolean enableReleaseCheck,

        int businessMinProcessedCount,
        String businessRequiredResultFilesCsv,

        boolean enforceIsolationGuard,
        boolean enforceSeparateEfsAccessPoints,
        boolean enableBusinessValidation,

        //Lambda
        LambdaConfig lambdaConfig,
        //watchDog
        WatchDogConfig watchDogConfig,
        //DynamoDb
        DynamoDBConfig dynamoDBConfig,
        //worker
        WorkerConfig workerConfig
) {
    /**
     * 환경변수 -> 타입 안전 설정 객체 변환
     */
    public static OnDemandWorkflowConfig fromEnv() {
        return new OnDemandWorkflowConfig(
                // 상태머신 식별
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_STATE_MACHINE_NAME),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKFLOW_TIMEOUT_MINUTES)),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_SCHEDULE_EXPRESSION),

                // scale
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_SCALE_UP_DESIRED_COUNT)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_SCALE_DOWN_DESIRED_COUNT)),

                // steady/alb polling
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_STEADY_POLL_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_STEADY_MAX_ATTEMPTS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ALB_HEALTH_POLL_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ALB_HEALTH_MAX_ATTEMPTS)),

                // ready gate
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_PROBE_TIMEOUT_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_RETRY_INTERVAL_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_MAX_ATTEMPTS)),

                Double.parseDouble(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_BACKOFF_RATE)),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_REQUIRED_CHECKS),

                // release consistency
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_EXPECTED_RELEASE_TAG),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_FASTAPI_IMAGE_TAG_PREFIX),
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ENABLE_RELEASE_CHECK)),

                // business validation
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_MIN_PROCESSED_COUNT)),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_REQUIRED_RESULT_FILES),

                // runtime isolation guard
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ENFORCE_ISOLATION_GUARD)),
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ENFORCE_SEPARATE_EFS_ACCESS_POINTS)),
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ENABLE_BUSINESS_VALIDATION)),

                // lambda assets + memory
                LambdaConfig.fromEnv(),
                WatchDogConfig.fromEnv(),
                DynamoDBConfig.fromEnv(),
                WorkerConfig.fromEnv()
        );
    }


    public boolean hasExpectedReleaseTag() {
        return expectedReleaseTag != null && !expectedReleaseTag.isBlank();
    }

    public List<String> readyRequiredChecks() {
        return splitCsv(readyRequiredChecksCsv);
    }

    public List<String> businessRequiredResultFiles() {
        return splitCsv(businessRequiredResultFilesCsv);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        // 공백/중복 제거로 운영 입력 실수의 영향을 최소화한다.
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
