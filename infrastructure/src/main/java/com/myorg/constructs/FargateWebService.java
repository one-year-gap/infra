package com.myorg.constructs;

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

/**
 * FargateService - Admin Web Service
 * <p>
 * - TaskDefinition: 컨테이너 실행 명세-스펙/역할/정의
 * - ContainerDefinition: 실제 컨테이너-이미지/로그/환경변수
 * - FargateService: TaskDefinition 기반으로 운영
 */
public class FargateWebService extends Construct {
    //TaskDefinition: container 실행 명세서
    private final FargateTaskDefinition taskDefinition;
    //service: 운영 단위이면서 리소스
    private final FargateService service;
    //containerDefinition: TaskDefinition 안에서 실행되는 컨테이너 1개 정의
    private final ContainerDefinition containerDefinition;

    /**
     * Construct ID / Resource ID 상수
     */
    private static final String TASK_DEFINITION = "TaskDef";
    private static final String EXECUTION_ROLE = "ExecRole";
    private static final String TASK_ROLE = "TaskRole";
    private static final String CONTAINER_ID = "Container";
    private static final String SERVICE_ID = "Service";

    /**
     * Fargate  스펙
     */
    private static final int SERVER_CPU = 256;
    private static final int SERVER_MEMORY = 512;

    /**
     * Web 컨테이너 환경변수
     */
    private static final String PORT = "PORT";


    public FargateWebService(
            Construct scope,
            String id,

            Cluster cluster,
            Repository repository,
            String imageTag,//Repo에서 어떤 태그 이미지를 가져올지

            SecurityGroup serviceSg,
            int containerPort,

            LogGroup logGroup,//CloudWatch Logs로 보내기 위한 LogGroup
            String logStreamPrefix,//서비스별 구분용 prefix

            SubnetSelection subnets,
            int desiredCount, //유지할 Task 개수
            boolean enableEcsExec//AWS ECS exectute-command 사용 여부
    ) {
        super(scope, id);

        /**
         * 1) TaskDefinition을 생성함.
         */
        this.taskDefinition = FargateTaskDefinition.Builder.create(this, TASK_DEFINITION)
                //기본 스펙 setup
                .cpu(SERVER_CPU)
                .memoryLimitMiB(SERVER_MEMORY)
                //ECS 시작 시 필요 권한 - ECR에서 이미지 pull->CloudWath Logs로 로그 스트림 전송
                .executionRole(createExecutionRole(EXECUTION_ROLE))
                //컨테이너 안 service가 AWS API 호출 권한
                .taskRole(enableEcsExec ? createTaskRoleWithExec(TASK_ROLE) : createBasicTaskRole(TASK_ROLE))
                .build();

        /**
         * 2) Container 추가
         */
        this.containerDefinition = taskDefinition.addContainer(CONTAINER_ID,
                ContainerDefinitionOptions.builder()
                        //image: ECR 레포에서 imageTag를 가져와 container 실행
                        .image(ContainerImage.fromEcrRepository(repository, imageTag))
                        //logging: CloudWatch Logs로 컨테이너 stdout/stderr 전송
                        .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(logGroup)
                                .streamPrefix(logStreamPrefix)
                                .build()))
                        .environment(Map.of(
                                PORT, String.valueOf(containerPort)
                        ))
                        .build()
        );

        /**
         * 3) Port Mapping
         */
        containerDefinition.addPortMappings(PortMapping.builder()
                //컨테이너가 containerPort에서 리스닝하는 것을 ECS에 알려줌
                .containerPort(containerPort)
                .protocol(Protocol.TCP)
                .build());

        /**
         * 4) FargateService 실행
         */
        this.service = FargateService.Builder.create(this, SERVICE_ID)
                .cluster(cluster)
                .taskDefinition(taskDefinition)
                //securityGroup: Task ENI에 적용할 Security Group
                .securityGroups(List.of(serviceSg))
                //vpcSubnets: Task가 배치되는 subnet
                .vpcSubnets(subnets)
                //assignPublicIp: public IP 없이 private에서 동작
                .assignPublicIp(false)
                //desiredCount: service가 항상 떠 있는 개수
                .desiredCount(desiredCount)
                .enableExecuteCommand(enableEcsExec)
                .build();
    }


    /**
     * Execution Role: ECS/Fargate 런타임이 Task 시작 시 필요 권한
     */
    private Role createExecutionRole(String id) {
        // 이미지 Pull + 로그 전송(ECR/Logs)에 필요
        return Role.Builder.create(this, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        //ECR image pull + CloudWatch Logs write 권한
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")
                ))
                .build();
    }

    /**
     * Basic Task Role: service가 AWS 호출 시 필요 권한
     */
    private Role createBasicTaskRole(String id) {
        return Role.Builder.create(this, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();
    }

    /*
     * Task Role with ECS Exec
     */
    private Role createTaskRoleWithExec(String id) {
        Role role = Role.Builder.create(this, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();

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

    public FargateTaskDefinition getTaskDefinition() {
        return taskDefinition;
    }

    public FargateService getService() {
        return service;
    }

    public ContainerDefinition getContainer() {
        return containerDefinition;
    }
}
