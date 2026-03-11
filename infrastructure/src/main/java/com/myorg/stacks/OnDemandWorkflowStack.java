package com.myorg.stacks;

import com.myorg.config.OnDemandWorkflowConfig;
import com.myorg.config.dynamodb.DynamoDBConfig;
import com.myorg.workflow.ondemand.OnDemandWorkflowDefinitionBuilder;
import com.myorg.workflow.ondemand.OnDemandWorkflowResources;
import com.myorg.workflow.ondemand.OnDemandSupportFunctionFactory;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Subnet;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcLookupOptions;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableAttributes;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.SfnStateMachine;
import software.amazon.awscdk.services.events.targets.SfnStateMachineProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.DefinitionBody;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineProps;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

/**
 * 스케줄 기반 analysis batch 워크플로우 스택.
 *
 * 1) EventBridge가 StateMachine을 시작
 * 2) StateMachine이 DynamoDB 락을 선점
 * 3) analysis-server readiness 확인
 * 4) ECS RunTask(APP_MODE=batch) 1회 실행
 * 5) 종료 확인/검증 후 락 해제
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
        IVpc workflowVpc = importWorkflowVpc(config);

        Function analysisServerProbeFunction = OnDemandSupportFunctionFactory.createAnalysisServerProbe(
                this,
                "AnalysisServerProbeFunction",
                config.analysisServerReadinessConfig(),
                workflowVpc,
                importSubnets(config.analysisServerReadinessConfig().probeSubnetIds()),
                importSecurityGroups(config.analysisServerReadinessConfig().probeSecurityGroupIds())
        );

        // 검증 로직은 배치 종료 후 선택적으로만 사용한다.
        Function businessValidatorFunction = null;
        if (config.enableBusinessValidation()) {
            businessValidatorFunction = OnDemandSupportFunctionFactory.createBusinessValidator(
                    this,
                    "BusinessValidatorFunction",
                    config
            );
        }

        /*
        * =================================================================
        * 상태머신 정의 생성
        * =================================================================
        */
        Chain definition = new OnDemandWorkflowDefinitionBuilder().build(
                this,
                resources,
                config,
                analysisServerProbeFunction,
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

        stateMachine.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(java.util.List.of("ecs:TagResource"))
                .resources(java.util.List.of(String.format(
                        "arn:aws:ecs:%s:%s:task/%s/*",
                        this.getRegion(),
                        this.getAccount(),
                        resources.clusterName()
                )))
                .build());

        if (!resources.workerConfig().workerTaskRoleArn().isBlank() || !resources.workerConfig().workerExecutionRoleArn().isBlank()) {
            java.util.List<String> passRoleResources = new java.util.ArrayList<>();
            if (!resources.workerConfig().workerTaskRoleArn().isBlank()) {
                passRoleResources.add(resources.workerConfig().workerTaskRoleArn());
            }
            if (!resources.workerConfig().workerExecutionRoleArn().isBlank()) {
                passRoleResources.add(resources.workerConfig().workerExecutionRoleArn());
            }

            // ECS RunTask가 worker task/execution role을 사용할 수 있도록 필요한 role만 PassRole 허용한다.
            stateMachine.addToRolePolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("iam:PassRole"))
                    .resources(passRoleResources)
                    .conditions(java.util.Map.of(
                            "StringEquals", java.util.Map.of("iam:PassedToService", "ecs-tasks.amazonaws.com")
                    ))
                    .build());
        }

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

        CfnOutput.Builder.create(this, "OnDemandWorkflowStateMachineArn")
                .value(stateMachine.getStateMachineArn())
                .description("Analysis batch workflow StateMachine ARN")
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

    private IVpc importWorkflowVpc(OnDemandWorkflowConfig config) {
        return Vpc.fromLookup(this, "ImportedOnDemandWorkflowVpc", VpcLookupOptions.builder()
                .vpcId(config.analysisServerReadinessConfig().probeVpcId())
                .build());
    }

    private List<ISubnet> importSubnets(List<String> subnetIds) {
        List<ISubnet> subnets = new ArrayList<>();
        for (int i = 0; i < subnetIds.size(); i++) {
            subnets.add(Subnet.fromSubnetId(this, "ImportedAnalysisServerProbeSubnet" + i, subnetIds.get(i)));
        }
        return subnets;
    }

    private List<ISecurityGroup> importSecurityGroups(List<String> securityGroupIds) {
        List<ISecurityGroup> securityGroups = new ArrayList<>();
        for (int i = 0; i < securityGroupIds.size(); i++) {
            securityGroups.add(SecurityGroup.fromSecurityGroupId(
                    this,
                    "ImportedAnalysisServerProbeSecurityGroup" + i,
                    securityGroupIds.get(i)
            ));
        }
        return securityGroups;
    }
}
