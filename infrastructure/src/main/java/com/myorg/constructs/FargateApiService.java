package com.myorg.constructs;

import com.myorg.config.ContainerConfig;
import com.myorg.props.FargateApiServiceProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.constructs.Construct;

import java.util.List;
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
 * - Spring profile(customer/admin) 설정
 * - DB 연결 정보 필요
 * - ECS Exec 활성화 가능
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

    /**
     * DB 환경변수
     */
    private static final String SPRING_DATASOURCE_USERNAME = "SPRING_DATASOURCE_USERNAME";
    private static final String SPRING_DATASOURCE_PASSWORD = "SPRING_DATASOURCE_PASSWORD";


    public FargateApiService(
            FargateApiServiceProps props
    ) {
        super(props.scope(), props.id());

        Role taskRole = props.enableEcsExec()
                ? createTaskRoleWithExec(TASK_ROLE, props.extraTaskPolicies())
                : createBasicTaskRole(TASK_ROLE, props.extraTaskPolicies());
        addSecretsManagerReadPolicy(taskRole, props.secretsManagerArns());

        /**
         * 1) TaskDefinition 생성
         */
        this.taskDefinition = FargateTaskDefinition.Builder.create(this, TASK_DEFINITION)
                .cpu(SERVER_CPU)
                .memoryLimitMiB(SERVER_MEMORY)
                .executionRole(createExecutionRole(EXECUTION_ROLE, props.extraExecutionPolicies()))
                .taskRole(taskRole)
                .build();

        //secret 주입 - executionRole에 read 권한 부여
        props.dbSecret().grantRead(Objects.requireNonNull(taskDefinition.getExecutionRole()));

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
                        .environment(Map.of(
                                SPRING_PROFILES_ACTIVE, props.springProfile(),
                                SPRING_DATASOURCE_URL, props.jdbcUrl(),
                                SERVER_PORT, String.valueOf(props.containerPort())
                        ))
                        .secrets(Map.of(
                                //Secret Manager JSON key("username")
                                SPRING_DATASOURCE_USERNAME, software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(props.dbSecret(), "username"),
                                //Secret Manage JSON key("password")
                                SPRING_DATASOURCE_PASSWORD, software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(props.dbSecret(), "password")
                        ))
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


        /**
         * 3) FargateService
         */

        FargateService.Builder serviceBuilder = FargateService.Builder.create(this, SERVICE_ID)
                .cluster(props.cluster())
                .taskDefinition(taskDefinition)
                .securityGroups(List.of(props.serviceSg()))
                .vpcSubnets(props.subnets())
                .assignPublicIp(false)
                //health check 추가 - 180s
                .healthCheckGracePeriod(Duration.seconds(180))
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

    /**
     * Execution Role: ECS/Fargate 런타임이 Task 시작 시 필요 권한
     */
    private Role createExecutionRole(String id, List<PolicyStatement> extraPolicies) {
        Role role = Role.Builder.create(this, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")
                ))
                .build();
        addExtraPolicies(role, extraPolicies);
        return role;
    }

    /**
     * Basic Task Role: service가 AWS 호출시 필요 권한
     */
    private Role createBasicTaskRole(String id, List<PolicyStatement> extraPolicies) {
        Role role = Role.Builder.create(this, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();
        addExtraPolicies(role, extraPolicies);
        return role;
    }

    /*
     * Task Role with ECS Exec
     */
    private Role createTaskRoleWithExec(String id, List<PolicyStatement> extraPolicies) {
        Role role = createBasicTaskRole(id, extraPolicies);

        role.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "ssmmessages:CreateControlChannel",
                        "ssmmessages:CreateDataChannel",
                        "ssmmessages:OpenControlChannel",
                        "ssmmessages:OpenDataChannel"
                ))
                .resources(List.of("*"))
                .build());

        return role;
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

    private void addExtraPolicies(Role role, List<PolicyStatement> extraPolicies) {
        if (extraPolicies == null || extraPolicies.isEmpty()) {
            return;
        }
        extraPolicies.forEach(role::addToPolicy);
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
}
