package com.myorg.config;

import software.amazon.awscdk.App;

import java.util.Arrays;
import java.util.List;

public record WorkerConfig(
        String workerBatchJobName,
        String workerRunWindow,
        String workerImageTagPrefix,

        int workerPollSeconds,
        int workerMaxAttempts,

        String workerInputBasePath,
        String workerOutputBasePath,
        String workerLockBasePath,

        String workerTaskDefinitionArn,
        String workerContainerName,

        List<String> workerSubnetIds,
        List<String> workerSecurityGroupIds

) {
    public static WorkerConfig fromEnv() {
        return new WorkerConfig(
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_BATCH_JOB_NAME),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_RUN_WINDOW),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_IMAGE_TAG_PREFIX),

                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_POLL_SECONDS)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_MAX_ATTEMPTS)),

                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_INPUT_BASE_PATH),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_OUTPUT_BASE_PATH),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_LOCK_BASE_PATH),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_TASK_DEFINITION_ARN),
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
