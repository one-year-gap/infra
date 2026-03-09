package com.myorg.props;

import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.servicediscovery.INamespace;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Python/worker/internal
 */
public record FargateBackgroundServiceProps(
        Construct scope,
        String id,

        Cluster cluster,
        IRepository repository,
        String imageTag,

        SecurityGroup serviceSg,
        ILogGroup logGroup,
        String logStreamPrefix,
        SubnetSelection subnets,

        int cpu,
        int memoryLimitMiB,
        int desiredCount,
        boolean enableEcsExec,

        Map<String, String> environment,
        List<String> command,
        Integer containerPort,

        ISecret runtimeSecret,
        Map<String, String> secretJsonKeyByEnvName,

        INamespace cloudMapNamespace,
        String cloudMapServiceName,

        List<PolicyStatement> extraExecutionPolicies,
        List<PolicyStatement> extraTaskPolicies
) {
}
