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

        String expectedReleaseTag,

        BusinessValidationMode businessValidationMode,
        int businessValidationPollSeconds,
        int businessValidationMaxAttempts,
        int businessMinProcessedCount,
        String businessRequiredResultFilesCsv,
        String businessValidatorDbSecretId,
        String businessValidatorDbName,

        boolean enableBusinessValidation,

        AnalysisServerReadinessConfig analysisServerReadinessConfig,
        LambdaConfig lambdaConfig,
        DynamoDBConfig dynamoDBConfig,
        WorkerConfig workerConfig
) {
    /**
     * 환경변수 -> 타입 안전 설정 객체 변환
     */
    public static OnDemandWorkflowConfig fromEnv() {
        return new OnDemandWorkflowConfig(
                // analysis batch 상태머신 식별 및 스케줄
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_STATE_MACHINE_NAME.key(), "AnalysisBatchWorkflow"),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_WORKFLOW_TIMEOUT_MINUTES.key(), "120")),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_SCHEDULE_EXPRESSION.key(), ""),

                // 배치 task에 override로 주입할 릴리즈 태그
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_EXPECTED_RELEASE_TAG.key(), ""),

                // 배치 종료 후 선택 검증
                BusinessValidationMode.fromEnv(
                        AppConfig.getOptionalValueOrDefault(
                                EnvKey.ON_DEMAND_BUSINESS_VALIDATION_MODE.key(),
                                EnvKey.ON_DEMAND_BUSINESS_VALIDATION_MODE.getDefaultValue()
                        )
                ),
                parsePositiveInt(EnvKey.ON_DEMAND_BUSINESS_VALIDATION_POLL_SECONDS),
                parsePositiveInt(EnvKey.ON_DEMAND_BUSINESS_VALIDATION_MAX_ATTEMPTS),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_MIN_PROCESSED_COUNT.key(), "0")),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_REQUIRED_RESULT_FILES.key(), ""),
                AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_DB_SECRET_ID.key(),
                        EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_DB_SECRET_ID.getDefaultValue()
                ),
                AppConfig.getOptionalValueOrDefault(
                        EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_DB_NAME.key(),
                        EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_DB_NAME.getDefaultValue()
                ),
                Boolean.parseBoolean(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_ENABLE_BUSINESS_VALIDATION.key(), "false")),

                AnalysisServerReadinessConfig.fromEnv(),
                LambdaConfig.fromEnv(),
                DynamoDBConfig.fromEnv(),
                WorkerConfig.fromEnv()
        );
    }

    public boolean hasExpectedReleaseTag() {
        return expectedReleaseTag != null && !expectedReleaseTag.isBlank();
    }

    public boolean usesOutboxRequestCountValidation() {
        return businessValidationMode == BusinessValidationMode.OUTBOX_REQUEST_COUNTS;
    }

    public List<String> businessRequiredResultFiles() {
        return splitCsv(businessRequiredResultFilesCsv);
    }

    private static int parsePositiveInt(EnvKey key) {
        int parsed = Integer.parseInt(AppConfig.getOptionalValueOrDefault(key.key(), key.getDefaultValue()));
        if (parsed <= 0) {
            throw new IllegalStateException(key.key() + " 값은 1 이상이어야 합니다.");
        }
        return parsed;
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
