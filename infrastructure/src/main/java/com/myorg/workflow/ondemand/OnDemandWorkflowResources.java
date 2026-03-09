package com.myorg.workflow.ondemand;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.config.WorkerConfig;
import software.constructs.Construct;

import java.util.List;

/**
 * analysis batch 워크플로우가 import해서 참조하는 운영 리소스 묶음.
 */
public record OnDemandWorkflowResources(
        String clusterArn,
        String clusterName,
        WorkerConfig workerConfig
) {

    /**
     * 기존 운영 리소스를 환경변수 기반으로 import
     */
    public static OnDemandWorkflowResources fromEnv(Construct scope) {
        String clusterArn = AppConfig.getRequiredValue(EnvKey.ON_DEMAND_CLUSTER_ARN.key());
        String clusterName = AppConfig.getOptionalValueOrDefault(
                EnvKey.ON_DEMAND_CLUSTER_NAME.key(),
                parseLastToken(clusterArn)
        );

        return new OnDemandWorkflowResources(
                clusterArn,
                clusterName,
                WorkerConfig.fromEnv()
        );
    }

    private static String parseLastToken(String arnOrPath) {
        if (arnOrPath == null || arnOrPath.isBlank()) {
            return arnOrPath;
        }
        String[] slashTokens = arnOrPath.split("/");
        return slashTokens[slashTokens.length - 1];
    }
}
