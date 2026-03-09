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

        int businessMinProcessedCount,
        String businessRequiredResultFilesCsv,

        boolean enableBusinessValidation,

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
                Integer.parseInt(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_MIN_PROCESSED_COUNT.key(), "0")),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_REQUIRED_RESULT_FILES.key(), ""),
                Boolean.parseBoolean(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_ENABLE_BUSINESS_VALIDATION.key(), "false")),

                // 검증/락/배치 실행에 필요한 지원 설정
                LambdaConfig.fromEnv(),
                DynamoDBConfig.fromEnv(),
                WorkerConfig.fromEnv()
        );
    }

    public boolean hasExpectedReleaseTag() {
        return expectedReleaseTag != null && !expectedReleaseTag.isBlank();
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
