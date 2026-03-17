package com.myorg.constructs;

import com.myorg.props.FargateBackgroundServiceProps;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * private ECS 서비스
 */
public class FargateBackgroundService extends Construct {
    private final FargateTaskDefinition taskDefinition;
    private final FargateService service;
    private final ContainerDefinition containerDefinition;

    public FargateBackgroundService(FargateBackgroundServiceProps props) {
        super(props.scope(), props.id());

        Role executionRole = FargateRoleFactory.createExecutionRole(
                this,
                "ExecRole",
                props.extraExecutionPolicies()
        );
        Role taskRole = props.enableEcsExec()
                ? FargateRoleFactory.createTaskRoleWithExec(this, "TaskRole", props.extraTaskPolicies())
                : FargateRoleFactory.createBasicTaskRole(this, "TaskRole", props.extraTaskPolicies());

        this.taskDefinition = FargateTaskDefinition.Builder.create(this, "TaskDef")
                .cpu(props.cpu())
                .memoryLimitMiB(props.memoryLimitMiB())
                .executionRole(executionRole)
                .taskRole(taskRole)
                .build();

        Map<String, software.amazon.awscdk.services.ecs.Secret> ecsSecrets = new HashMap<>();
        if (props.runtimeSecret() != null) {
            props.runtimeSecret().grantRead(Objects.requireNonNull(taskDefinition.getExecutionRole()));
            props.secretJsonKeyByEnvName().forEach((envName, jsonKey) ->
                    ecsSecrets.put(
                            envName,
                            software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(props.runtimeSecret(), jsonKey)
                    )
            );
        }

        ContainerDefinitionOptions.Builder containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromEcrRepository(props.repository(), props.imageTag()))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(props.logGroup())
                        .streamPrefix(props.logStreamPrefix())
                        .build()))
                .environment(props.environment())
                .secrets(ecsSecrets);

        if (props.entryPoint() != null && !props.entryPoint().isEmpty()) {
            containerOptions.entryPoint(props.entryPoint());
        }

        if (props.command() != null && !props.command().isEmpty()) {
            containerOptions.command(props.command());
        }

        this.containerDefinition = taskDefinition.addContainer("MainContainer", containerOptions.build());

        if (props.containerPort() != null) {
            containerDefinition.addPortMappings(PortMapping.builder()
                    .containerPort(props.containerPort())
                    .protocol(Protocol.TCP)
                    .build());
        }

        FargateService.Builder serviceBuilder = FargateService.Builder.create(this, "Service")
                .cluster(props.cluster())
                .taskDefinition(taskDefinition)
                .securityGroups(List.of(props.serviceSg()))
                .vpcSubnets(props.subnets())
                .assignPublicIp(false)
                .desiredCount(props.desiredCount())
                .enableExecuteCommand(props.enableEcsExec());

        if (props.serviceName() != null && !props.serviceName().isBlank()) {
            serviceBuilder.serviceName(props.serviceName());
        }

        if (props.cloudMapNamespace() != null
                && props.cloudMapServiceName() != null
                && !props.cloudMapServiceName().isBlank()) {
            serviceBuilder.cloudMapOptions(CloudMapOptions.builder()
                    .cloudMapNamespace(props.cloudMapNamespace())
                    .name(props.cloudMapServiceName())
                    .build());
        }

        this.service = serviceBuilder.build();
    }

    public FargateTaskDefinition getTaskDefinition() {
        return taskDefinition;
    }

    public FargateService getService() {
        return service;
    }

    public ContainerDefinition getContainerDefinition() {
        return containerDefinition;
    }
}
