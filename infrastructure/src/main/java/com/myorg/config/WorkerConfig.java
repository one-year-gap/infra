package com.myorg.config;

import software.amazon.awscdk.App;

import java.util.Arrays;
import java.util.List;

public record WorkerConfig(
        String workerRunWindow,

        int workerPollSeconds,
        int workerMaxAttempts,

        String workerInputBasePath,
        String workerOutputBasePath,
        String workerLockBasePath,

        String workerTaskDefinitionArn,
        String workerTaskDefinitionFamily,
        String workerContainerName,

        List<String> workerSubnetIds,
        List<String> workerSecurityGroupIds

) {
    public static WorkerConfig fromEnv() {
        WorkerConfig config = new WorkerConfig(
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_RUN_WINDOW),

                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_POLL_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_MAX_ATTEMPTS)),

                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_INPUT_BASE_PATH),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_OUTPUT_BASE_PATH),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_LOCK_BASE_PATH),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_WORKER_TASK_DEFINITION_ARN.key(), ""),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_WORKER_TASK_DEFINITION_FAMILY.key(), ""),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_CONTAINER_NAME),

                parsingToNonEmptyList(
                        AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_SUBNET_IDS),
                        EnvKey.ON_DEMAND_WORKER_SUBNET_IDS.key()
                ),
                parsingToNonEmptyList(
                        AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_SECURITY_GROUP_IDS),
                        EnvKey.ON_DEMAND_WORKER_SECURITY_GROUP_IDS.key()
                )
        );

        if (config.workerTaskDefinitionArn().isBlank() && config.workerTaskDefinitionFamily().isBlank()) {
            throw new IllegalStateException("ON_DEMAND_WORKER_TASK_DEFINITION_ARN 또는 ON_DEMAND_WORKER_TASK_DEFINITION_FAMILY 중 하나는 필요합니다.");
        }

        return config;
    }

    /**
     * batch 실행 시에는 family를 우선 사용해 항상 최신 ACTIVE revision을 바라본다.
     */
    public String workerTaskDefinitionIdentifier() {
        if (workerTaskDefinitionFamily != null && !workerTaskDefinitionFamily.isBlank()) {
            return workerTaskDefinitionFamily;
        }
        return workerTaskDefinitionArn;
    }

    private static List<String> parsingToNonEmptyList(String origin, String key){
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException(key + " 값이 비어 있습니다.");
        }
        String[] parsing = origin.replaceAll(" ","").split(",");
        List<String> values = Arrays.stream(parsing)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException(key + " 값이 비어 있습니다.");
        }
        return values;
    }
}
