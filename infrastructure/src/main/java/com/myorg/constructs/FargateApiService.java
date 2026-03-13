package com.myorg.constructs;

import com.myorg.config.AppConfig;
import com.myorg.config.ContainerConfig;
import com.myorg.props.FargateApiServiceProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * FargateService - API Service (Customer/Admin 공용)
 *
 * <p>
 * - TaskDefinition: 컨테이너 실행 명세-스펙/역할/정의
 * - ContainerDefinition: 실제 컨테이너-이미지/로그/환경변수
 * - FargateService: TaskDefinition 기반으로 운영
 * <p>
 */
public class FargateApiService extends Construct {
    //TaskDefinition: container 실행 명세서
    private final FargateTaskDefinition taskDefinition;
    //service: 운영 단위이면서 리소스
    private final FargateService service;
    //containerDefinition: TaskDefinition 안에서 실행되는 컨테이너 정의
    private final ContainerDefinition containerDefinition;


    /**
     * CDK Construct ID / 리소스 ID 상수
     */
    private static final String EXECUTION_ROLE = "ExecRole";
    private static final String TASK_DEFINITION = "TaskDef";
    private static final String TASK_ROLE = "TaskRole";
    private static final String CONTAINER_ID = ContainerConfig.API_CONTAINER_NAME;
    private static final String SERVICE_ID = "Service";

    /**
     * Fargate spec
     */
    private static final int SERVER_CPU = 256;
    private static final int SERVER_MEMORY = 512;

    /**
     * Spring 환경변수 키 상수
     */
    private static final String SPRING_PROFILES_ACTIVE = "SPRING_PROFILES_ACTIVE";
    private static final String SPRING_DATASOURCE_URL = "SPRING_DATASOURCE_URL";
    private static final String SERVER_PORT = "SERVER_PORT";
    private static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";

    /**
     * DB 환경변수
     */
    private static final String SPRING_DATASOURCE_USERNAME = "SPRING_DATASOURCE_USERNAME";
    private static final String SPRING_DATASOURCE_PASSWORD = "SPRING_DATASOURCE_PASSWORD";

    /**
     * Pinpoint agent init container
     */
    private static final String PINPOINT_INIT_CONTAINER_ID = "PinpointAgentInit";
    private static final String PINPOINT_AGENT_VOLUME = "PinpointAgentVolume";
    private static final String PINPOINT_SHARE_DIR = "/pinpoint-agent-share";


    public FargateApiService(
            FargateApiServiceProps props
    ) {
        super(props.scope(), props.id());

        Role taskRole = props.enableEcsExec()
                ? FargateRoleFactory.createTaskRoleWithExec(this, TASK_ROLE, props.extraTaskPolicies())
                : FargateRoleFactory.createBasicTaskRole(this, TASK_ROLE, props.extraTaskPolicies());
        addSecretsManagerReadPolicy(taskRole, props.secretsManagerArns());

        /**
         * 1) TaskDefinition 생성
         */
        this.taskDefinition = FargateTaskDefinition.Builder.create(this, TASK_DEFINITION)
                .cpu(SERVER_CPU)
                .memoryLimitMiB(SERVER_MEMORY)
                .executionRole(FargateRoleFactory.createExecutionRole(this, EXECUTION_ROLE, props.extraExecutionPolicies()))
                .taskRole(taskRole)
                .build();

        //secret 주입 - executionRole에 read 권한 부여
        props.dbSecret().grantRead(Objects.requireNonNull(taskDefinition.getExecutionRole()));

        Map<String, String> environment = buildBaseEnvironment(props);
        Map<String, software.amazon.awscdk.services.ecs.Secret> datasourceSecrets = new LinkedHashMap<>();
        datasourceSecrets.put(
                SPRING_DATASOURCE_PASSWORD,
                software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(props.dbSecret(), "password")
        );
        datasourceSecrets.put(
                SPRING_DATASOURCE_USERNAME,
                software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(props.dbSecret(), "username")
        );
        PinpointSettings pinpointSettings = resolvePinpointSettings(props);

        ContainerDefinition pinpointInitContainer = null;
        if (pinpointSettings.enabled()) {
            taskDefinition.addVolume(Volume.builder()
                    .name(PINPOINT_AGENT_VOLUME)
                    .build());

            pinpointInitContainer = taskDefinition.addContainer(PINPOINT_INIT_CONTAINER_ID,
                    ContainerDefinitionOptions.builder()
                            .image(ContainerImage.fromRegistry(pinpointSettings.agentImage()))
                            .essential(false)
                            .command(List.of(
                                    "sh",
                                    "-lc",
                                    "set -e; " +
                                            "if [ -d /pinpoint-agent ]; then cp -a /pinpoint-agent/. " + PINPOINT_SHARE_DIR + "/; " +
                                            "elif [ -d /opt/pinpoint-agent ]; then cp -a /opt/pinpoint-agent/. " + PINPOINT_SHARE_DIR + "/; " +
                                            "else echo 'Pinpoint agent directory not found' >&2; exit 1; fi"
                            ))
                            .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                    .logGroup(props.logGroup())
                                    .streamPrefix(props.logStreamPrefix() + "-pinpoint-init")
                                    .build()))
                            .build()
            );
            pinpointInitContainer.addMountPoints(MountPoint.builder()
                    .sourceVolume(PINPOINT_AGENT_VOLUME)
                    .containerPath(PINPOINT_SHARE_DIR)
                    .readOnly(false)
                    .build());

            environment.put(JAVA_TOOL_OPTIONS, pinpointSettings.javaToolOptions());
        }

        /**
         * 2) Container 추가
         */
        this.containerDefinition = taskDefinition.addContainer(CONTAINER_ID,
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(props.repository(), props.imageTag()))
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(props.logGroup())
                                .streamPrefix(props.logStreamPrefix())
                                .build()))
                        .environment(environment)
                        .secrets(datasourceSecrets)
                        .build()
        );

        /**
         * 3) Port Mapping
         */
        containerDefinition.addPortMappings(PortMapping.builder()
                .containerPort(props.containerPort())
                .protocol(Protocol.TCP)
                .build()
        );

        if (pinpointSettings.enabled() && pinpointInitContainer != null) {
            containerDefinition.addMountPoints(MountPoint.builder()
                    .sourceVolume(PINPOINT_AGENT_VOLUME)
                    .containerPath(pinpointSettings.agentMountPath())
                    .readOnly(true)
                    .build());
            containerDefinition.addContainerDependencies(ContainerDependency.builder()
                    .container(pinpointInitContainer)
                    .condition(ContainerDependencyCondition.SUCCESS)
                    .build());
        }


        /**
         * 3) FargateService
         */

        FargateService.Builder serviceBuilder = FargateService.Builder.create(this, SERVICE_ID)
                .cluster(props.cluster())
                .taskDefinition(taskDefinition)
                .securityGroups(List.of(props.serviceSg()))
                .vpcSubnets(props.subnets())
                .assignPublicIp(false)
                // Pinpoint agent 초기화 시간을 고려해 grace period를 넉넉히 설정
                .healthCheckGracePeriod(Duration.seconds(300))
                .desiredCount(props.desiredCount())
                .enableExecuteCommand(props.enableEcsExec());

        //Cloud Map 설정이 있으면
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

    private void addSecretsManagerReadPolicy(Role role, List<String> secretsManagerArns) {
        if (secretsManagerArns == null || secretsManagerArns.isEmpty()) {
            return;
        }

        role.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "secretsmanager:GetSecretValue",
                        "secretsmanager:DescribeSecret"
                ))
                .resources(secretsManagerArns)
                .build());
    }


    /**
     * getter
     */
    public FargateTaskDefinition getTaskDefinition() {
        return taskDefinition;
    }

    public FargateService getService() {
        return service;
    }

    public ContainerDefinition getContainerDefinition() {
        return containerDefinition;
    }

    private Map<String, String> buildBaseEnvironment(FargateApiServiceProps props) {
        Map<String, String> env = new HashMap<>();
        env.put(SPRING_PROFILES_ACTIVE, props.springProfile());
        env.put(SPRING_DATASOURCE_URL, props.jdbcUrl());
        env.put(SERVER_PORT, String.valueOf(props.containerPort()));
        // 서비스별 내부 통신 주소나 런타임 연동값은 호출 스택에서만 주입한다.
        if (props.extraEnvironment() != null && !props.extraEnvironment().isEmpty()) {
            env.putAll(props.extraEnvironment());
        }
        return env;
    }

    private PinpointSettings resolvePinpointSettings(FargateApiServiceProps props) {
        boolean enabled = Boolean.parseBoolean(AppConfig.getOptionalValueOrDefault("PINPOINT_ECS_ENABLE", "false"));
        if (!enabled) {
            return PinpointSettings.disabled();
        }

        String collectorHost = AppConfig.getOptionalValueOrDefault("PINPOINT_COLLECTOR_HOST", "");
        if (collectorHost.isBlank()) {
            return PinpointSettings.disabled();
        }

        String agentImage = AppConfig.getOptionalValueOrDefault("PINPOINT_AGENT_IMAGE", "pinpointdocker/pinpoint-agent:3.0.4");
        String agentMountPath = AppConfig.getOptionalValueOrDefault("PINPOINT_AGENT_MOUNT_PATH", "/opt/pinpoint-agent");
        String bootstrapJar = AppConfig.getOptionalValueOrDefault("PINPOINT_AGENT_BOOTSTRAP_JAR", "pinpoint-bootstrap.jar");

        String profilePrefix = "PINPOINT_" + props.springProfile().toUpperCase(Locale.ROOT);
        String applicationName = AppConfig.getOptionalValueOrDefault(
                profilePrefix + "_APPLICATION_NAME",
                props.logStreamPrefix()
        );
        String agentId = AppConfig.getOptionalValueOrDefault(
                profilePrefix + "_AGENT_ID",
                applicationName
        );

        String agentPort = AppConfig.getOptionalValueOrDefault("PINPOINT_COLLECTOR_AGENT_PORT", "9991");
        String metadataPort = AppConfig.getOptionalValueOrDefault("PINPOINT_COLLECTOR_METADATA_PORT", agentPort);
        String statPort = AppConfig.getOptionalValueOrDefault("PINPOINT_COLLECTOR_STAT_PORT", "9992");
        String spanPort = AppConfig.getOptionalValueOrDefault("PINPOINT_COLLECTOR_SPAN_PORT", "9993");

        String javaToolOptions = String.join(" ",
                "-javaagent:" + agentMountPath + "/" + bootstrapJar,
                "-Dpinpoint.applicationName=" + applicationName,
                "-Dpinpoint.agentId=" + agentId,
                "-Dprofiler.transport.module=GRPC",
                "-Dprofiler.transport.grpc.collector.ip=" + collectorHost,
                "-Dprofiler.transport.grpc.agent.collector.port=" + agentPort,
                "-Dprofiler.transport.grpc.metadata.collector.port=" + metadataPort,
                "-Dprofiler.transport.grpc.stat.collector.port=" + statPort,
                "-Dprofiler.transport.grpc.span.collector.port=" + spanPort,
                "-Dprofiler.sampling.type=COUNTING",
                "-Dprofiler.sampling.counting.sampling-rate=1",
                "-Dprofiler.sampling.percent.sampling-rate=100",
                "-Dprofiler.sampling.new.throughput=0",
                "-Dprofiler.sampling.continue.throughput=0"
        );

        return new PinpointSettings(true, agentImage, agentMountPath, javaToolOptions);
    }

    private record PinpointSettings(
            boolean enabled,
            String agentImage,
            String agentMountPath,
            String javaToolOptions
    ) {
        private static PinpointSettings disabled() {
            return new PinpointSettings(false, "", "", "");
        }
    }
}
