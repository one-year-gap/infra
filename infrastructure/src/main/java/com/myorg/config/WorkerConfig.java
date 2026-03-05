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

                parsingToList(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_PROBE_SUBNET_IDS)),
                parsingToList(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WORKER_SECURITY_GROUP_IDS))
        );
    }

    private static List<String> parsingToList(String origin){
        String[] parsing = origin.replaceAll(" ","").split(",");

        return Arrays.stream(parsing).toList();
    }
}
