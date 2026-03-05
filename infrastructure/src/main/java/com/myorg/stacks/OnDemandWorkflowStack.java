package com.myorg.stacks;

import com.myorg.config.dynamodb.DynamoDBConfig;
import com.myorg.workflow.ondemand.OnDemandSupportFunctionFactory;
import com.myorg.config.OnDemandWorkflowConfig;
import com.myorg.workflow.ondemand.OnDemandWorkflowDefinitionBuilder;
import com.myorg.workflow.ondemand.OnDemandWorkflowResources;
import com.myorg.workflow.ondemand.ReadyProbeFunctionFactory;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableAttributes;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.events.targets.SfnStateMachine;
import software.amazon.awscdk.services.events.targets.SfnStateMachineProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.DefinitionBody;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineProps;
import software.constructs.Construct;

/**
 * 온디맨드 FastAPI + Worker 워크플로우 스택.
 *
 * 1) FastAPI: default desired=0 / 실행: 1 / 0으로 복구
 * 2) Step Functions: 기동 -> 준비 확인 -> 배치 -> 종료
 * 3) DynamoDB lock + Watchdog로 운영 중 실제 사고 예방
 */
public class OnDemandWorkflowStack extends Stack {
    /**
     * 워크플로우의 단일 진입
     * - 수동 실행(StartExecution)
     * - EventBridge 스케줄 실행
     */
    private StateMachine stateMachine;

    /**
     * 동시 실행 제어용 분산락 repo
     * - 같은 lockKey로 이미 실행 중 -> 새 실행을 차단
     */
    private ITable lockTable;

    public OnDemandWorkflowStack(
            Construct scope,
            String id,
            StackProps props,
            OnDemandWorkflowConfig config
    ) {
        super(scope, id, props);
        OnDemandWorkflowResources resources = OnDemandWorkflowResources.fromEnv(this);
        initialize(resources, config);
    }

    public OnDemandWorkflowStack(
            Construct scope,
            String id,
            StackProps props,
            OnDemandWorkflowResources resources,
            OnDemandWorkflowConfig config
    ) {
        super(scope, id, props);
        initialize(resources, config);
    }

    private void initialize(OnDemandWorkflowResources resources, OnDemandWorkflowConfig config) {
        this.lockTable = importLockTable();

        /*
        * =================================================================
        * 보조 Lambda 생성
        * =================================================================
        */
        Function readyProbeFunction = ReadyProbeFunctionFactory.create(
                this,
                "ReadyProbeFunction",
                resources,
                config
        );

        Function runtimeGuardFunction = OnDemandSupportFunctionFactory.createRuntimeGuard(
                this,
                "RuntimeGuardFunction",
                config
        );
        // Runtime guard: ECS/EC2/IAM 조회형 API를 사용 -> 격리 위반 여부 검증
        runtimeGuardFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(java.util.List.of(
                        "ecs:DescribeTaskDefinition",
                        "ec2:DescribeSecurityGroups",
                        "iam:SimulatePrincipalPolicy"
                ))
                .resources(java.util.List.of("*"))
                .build());

        Function businessValidatorFunction = OnDemandSupportFunctionFactory.createBusinessValidator(
                this,
                "BusinessValidatorFunction",
                config
        );

        /*
        * =================================================================
        * 상태머신 정의 생성
        * =================================================================
        */
        Chain definition = new OnDemandWorkflowDefinitionBuilder().build(
                this,
                resources,
                config,
                readyProbeFunction,
                runtimeGuardFunction,
                businessValidatorFunction,
                lockTable.getTableName(),
                lockTable.getTableArn()
        );

        this.stateMachine = new StateMachine(this, "OnDemandStateMachine", StateMachineProps.builder()
                .stateMachineName(config.stateMachineName())
                .definitionBody(DefinitionBody.fromChainable(definition))
                .timeout(Duration.minutes(config.workflowTimeoutMinutes()))
                .tracingEnabled(true)
                .build());

        /*
        * =================================================================
        * 실행 스케줄
        * =================================================================
        */
        if (config.scheduleExpression() != null && !config.scheduleExpression().isBlank()) {
            Rule scheduleRule = Rule.Builder.create(this, "OnDemandScheduleRule")
                    .schedule(Schedule.expression(config.scheduleExpression()))
                    .build();
            scheduleRule.addTarget(new SfnStateMachine(
                    stateMachine,
                    SfnStateMachineProps.builder().build()
            ));
        }
        /*
        * =================================================================
        * 강제 종료/권한오류 대비 Watchdog
        * =================================================================
        */
        if (config.watchDogConfig().enableWatchdog() && config.watchDogConfig().watchdogScheduleExpression() != null && !config.watchDogConfig().watchdogScheduleExpression().isBlank()) {
            Function watchdogFunction = OnDemandSupportFunctionFactory.createWatchdog(
                    this,
                    "OnDemandWatchdogFunction",
                    config
            );

            watchdogFunction.addEnvironment("CLUSTER_ARN", resources.clusterArn());
            watchdogFunction.addEnvironment("FASTAPI_SERVICE_NAME", resources.fastApiServiceName());
            watchdogFunction.addEnvironment("STATE_MACHINE_ARN", stateMachine.getStateMachineArn());
            watchdogFunction.addEnvironment("IDLE_MINUTES", String.valueOf(config.watchDogConfig().watchdogIdleMinutes()));

            // Watchdog는 조회 + 보정(updateService desired=0) 권한만 최소 부여
            watchdogFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of(
                            "ecs:DescribeServices",
                            "ecs:UpdateService",
                            "states:ListExecutions"
                    ))
                    .resources(java.util.List.of("*"))
                    .build());

            Rule watchdogRule = Rule.Builder.create(this, "OnDemandWatchdogScheduleRule")
                    .schedule(Schedule.expression(config.watchDogConfig().watchdogScheduleExpression()))
                    .build();


            watchdogRule.addTarget(new LambdaFunction(watchdogFunction));
        }


        CfnOutput.Builder.create(this, "OnDemandWorkflowStateMachineArn")
                .value(stateMachine.getStateMachineArn())
                .description("OnDemand FastAPI workflow StateMachine ARN")
                .build();

        CfnOutput.Builder.create(this, "OnDemandWorkflowLockTableName")
                .value(lockTable.getTableName())
                .description("DynamoDB lock table name for on-demand workflow")
                .build();

        CfnOutput.Builder.create(this, "OnDemandWorkflowLockTableArn")
                .value(lockTable.getTableArn())
                .description("DynamoDB lock table ARN for on-demand workflow")
                .build();
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public ITable getLockTable() {
        return lockTable;
    }

    private ITable importLockTable() {
        DynamoDBConfig config=DynamoDBConfig.fromEnv();

        String lockTableName = config.lockTableName();
        String lockTableArn = config.lockTableArn();

        if (!lockTableArn.isBlank()) {
            return Table.fromTableAttributes(this, "ImportedOnDemandWorkflowLockTable", TableAttributes.builder()
                    .tableArn(lockTableArn)
                    .build());
        }

        return Table.fromTableName(this, "ImportedOnDemandWorkflowLockTable", lockTableName);
    }
}
