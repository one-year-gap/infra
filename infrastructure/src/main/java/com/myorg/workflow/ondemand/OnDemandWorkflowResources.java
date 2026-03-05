package com.myorg.workflow.ondemand;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.config.WorkerConfig;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetFilter;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcLookupOptions;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 온디맨드 워크플로우 참조
 */
public record OnDemandWorkflowResources(
        String clusterArn,
        String clusterName,

        String fastApiServiceArn,
        String fastApiServiceName,
        String fastApiTargetGroupArn,
        String readyUrl,

        WorkerConfig workerConfig,

        IVpc readyProbeVpc,
        ISecurityGroup readyProbeSecurityGroup,
        SubnetSelection readyProbeSubnets,

        String fastApiSecurityGroupId,
        String rdsSecurityGroupId,
        String rdsSecretArn,

        String fastApiEfsAccessPointId,
        String workerEfsAccessPointId
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

        String fastApiServiceArn = AppConfig.getRequiredValue(EnvKey.ON_DEMAND_FASTAPI_SERVICE_ARN.key());
        String fastApiServiceName = AppConfig.getOptionalValueOrDefault(
                EnvKey.ON_DEMAND_FASTAPI_SERVICE_NAME.key(),
                parseLastToken(fastApiServiceArn)
        );

        String readyProbeVpcId = AppConfig.getRequiredValue(EnvKey.ON_DEMAND_READY_PROBE_VPC_ID.key());
        String readyProbeSecurityGroupId = AppConfig.getRequiredValue(EnvKey.ON_DEMAND_READY_PROBE_SECURITY_GROUP_ID.key());
        List<String> readyProbeSubnetIds = parseCsvRequired(EnvKey.ON_DEMAND_READY_PROBE_SUBNET_IDS.key());

        IVpc readyProbeVpc = Vpc.fromLookup(scope, "OnDemandReadyProbeVpc", VpcLookupOptions.builder()
                .vpcId(readyProbeVpcId)
                .build());

        ISecurityGroup readyProbeSg = SecurityGroup.fromSecurityGroupId(
                scope,
                "OnDemandReadyProbeSecurityGroup",
                readyProbeSecurityGroupId
        );

        SubnetSelection readyProbeSubnets = SubnetSelection.builder()
                .subnetFilters(List.of(SubnetFilter.byIds(readyProbeSubnetIds)))
                .build();

        return new OnDemandWorkflowResources(
                clusterArn,
                clusterName,
                fastApiServiceArn,
                fastApiServiceName,
                AppConfig.getRequiredValue(EnvKey.ON_DEMAND_FASTAPI_TARGET_GROUP_ARN.key()),
                AppConfig.getRequiredValue(EnvKey.ON_DEMAND_READY_URL.key()),

                WorkerConfig.fromEnv(),

                readyProbeVpc,
                readyProbeSg,
                readyProbeSubnets,
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_FASTAPI_SECURITY_GROUP_ID.key(), ""),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_RDS_SECURITY_GROUP_ID.key(), ""),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_RDS_SECRET_ARN.key(), ""),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_FASTAPI_EFS_ACCESS_POINT_ID.key(), ""),
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_WORKER_EFS_ACCESS_POINT_ID.key(), "")
        );
    }

    /**
     * Runtime guard Lambda가 이 목록을 사용해 자동 주입 여부를 검증
     */
    public List<String> isolationDisallowedEnvNames() {
        return List.of(
                "SPRING_DATASOURCE_URL",
                "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD",
                "DB_URL",
                "DB_USER",
                "DB_PASSWORD"
        );
    }

    private static String parseLastToken(String arnOrPath) {
        if (arnOrPath == null || arnOrPath.isBlank()) {
            return arnOrPath;
        }
        String[] slashTokens = arnOrPath.split("/");
        return slashTokens[slashTokens.length - 1];
    }

    private static List<String> parseCsvRequired(String key) {
        String csv = AppConfig.getRequiredValue(key);
        List<String> values = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (values.isEmpty()) {
            throw new IllegalStateException(key + "는 비어 있을 수 없습니다.");
        }
        return values;
    }
}
