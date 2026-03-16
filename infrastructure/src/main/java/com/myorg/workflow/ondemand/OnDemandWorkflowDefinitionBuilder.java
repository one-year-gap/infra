package com.myorg.workflow.ondemand;

import com.myorg.config.OnDemandWorkflowConfig;
import com.myorg.config.WorkerConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.stepfunctions.CatchProps;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.Fail;
import software.amazon.awscdk.services.stepfunctions.FailProps;
import software.amazon.awscdk.services.stepfunctions.Pass;
import software.amazon.awscdk.services.stepfunctions.PassProps;
import software.amazon.awscdk.services.stepfunctions.Result;
import software.amazon.awscdk.services.stepfunctions.RetryProps;
import software.amazon.awscdk.services.stepfunctions.Succeed;
import software.amazon.awscdk.services.stepfunctions.TaskInput;
import software.amazon.awscdk.services.stepfunctions.Wait;
import software.amazon.awscdk.services.stepfunctions.WaitProps;
import software.amazon.awscdk.services.stepfunctions.WaitTime;
import software.amazon.awscdk.services.stepfunctions.tasks.CallAwsService;
import software.amazon.awscdk.services.stepfunctions.tasks.CallAwsServiceProps;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvokeProps;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 배치 전용 Step Functions 정의 빌더.
 *
 * 목적:
 * - 추천 실시간 서비스와 분리된 1회성 Spring Batch 실행
 * - DynamoDB conditional put으로 선점 락 보장
 * - ECS RunTask 완료 및 exit code 검증
 * - 비동기 분석 완료까지 선택적 business validation poll
 * - 항상 analysis-server scale down 및 lock 해제
 */
public class OnDemandWorkflowDefinitionBuilder {

    /**
     * 워크플로우 상태 전이:
     * 1) lock 획득
     * 2) analysis-server /ready, /health 확인
     * 3) Spring Batch task 실행
     * 4) task 종료/exit code 확인
     * 5) 선택적 business validation
     * 6) lock 해제
     */
    public Chain build(
            Construct scope,
            OnDemandWorkflowResources resources,
            OnDemandWorkflowConfig config,
            IFunction analysisServerProbeFunction,
            IFunction businessValidatorFunction,
            String lockTableName,
            String lockTableArn
    ) {
        String workerTaskDefinitionIdentifier = resources.workerConfig().workerTaskDefinitionIdentifier();
        var readinessConfig = config.analysisServerReadinessConfig();

        Pass markFailureFromCatch = new Pass(scope, "MarkFailureFromCatch", PassProps.builder()
                .parameters(Map.of(
                        "status", "FAILED",
                        "reason", "TASK_EXCEPTION",
                        "error.$", "$.error.Error",
                        "cause.$", "$.error.Cause"
                ))
                .resultPath("$.workflow")
                .build());

        Pass markRunTaskFailure = marker(scope, "MarkRunTaskFailure", "BATCH_RUN_TASK_FAILED");
        Pass markWorkerTimeout = marker(scope, "MarkWorkerTimeout", "BATCH_TASK_TIMEOUT");
        Pass markBatchFailure = marker(scope, "MarkBatchFailure", "BATCH_EXIT_CODE_NON_ZERO");
        Pass markAnalysisServerScaleUpFailure = marker(scope, "MarkAnalysisServerScaleUpFailure", "ANALYSIS_SERVER_SCALE_UP_FAILED");
        Pass markAnalysisServerReadyFailure = marker(scope, "MarkAnalysisServerReadyFailure", "ANALYSIS_SERVER_READY_FAILED");
        Pass markAnalysisServerHealthFailure = marker(scope, "MarkAnalysisServerHealthFailure", "ANALYSIS_SERVER_HEALTH_FAILED");
        Pass markAnalysisServerScaleDownFailure = marker(scope, "MarkAnalysisServerScaleDownFailure", "ANALYSIS_SERVER_SCALE_DOWN_FAILED");
        Pass markBusinessValidationFailure = businessValidatorFunction == null
                ? null
                : marker(scope, "MarkBusinessValidationFailure", "BUSINESS_VALIDATION_FAILED");
        Pass markBusinessValidationTimeout = businessValidatorFunction == null
                ? null
                : marker(scope, "MarkBusinessValidationTimeout", "BUSINESS_VALIDATION_TIMEOUT");
        Pass markSuccess = successMarker(scope);

        // 같은 lockKey에 대해 putItem을 선점으로 사용해 동시 실행을 차단한다.
        CallAwsService acquireExecutionLock = new CallAwsService(scope, "AcquireExecutionLock", CallAwsServiceProps.builder()
                .service("dynamodb")
                .action("putItem")
                .iamResources(List.of(lockTableArn))
                .parameters(Map.of(
                        "TableName", lockTableName,
                        "Item", Map.of(
                                "lockKey", Map.of("S", config.dynamoDBConfig().lockKey()),
                                "ownerExecutionId", Map.of("S.$", "$$.Execution.Id"),
                                "createdAt", Map.of("S.$", "$$.State.EnteredTime")
                        ),
                        "ConditionExpression", "attribute_not_exists(lockKey)"
                ))
                .resultPath("$.lockAcquireResult")
                .build());
        acquireExecutionLock.addRetry(awsApiRetry());

        Fail concurrentExecution = new Fail(scope, "ConcurrentExecution", FailProps.builder()
                .error("ConcurrentExecution")
                .cause("Another batch execution already holds the lock.")
                .build());
        acquireExecutionLock.addCatch(concurrentExecution, CatchProps.builder()
                .errors(List.of("DynamoDB.ConditionalCheckFailedException", "DynamoDb.ConditionalCheckFailedException"))
                .resultPath("$.error")
                .build());

        Fail lockAcquireFailed = new Fail(scope, "LockAcquireFailed", FailProps.builder()
                .error("LockAcquireFailed")
                .cause("Failed to acquire workflow lock.")
                .build());
        acquireExecutionLock.addCatch(lockAcquireFailed, CatchProps.builder()
                .resultPath("$.error")
                .build());

        Pass setRuntimeFlags = new Pass(scope, "SetRuntimeFlags", PassProps.builder()
                .result(Result.fromObject(Map.of(
                        "businessValidationEnabled", config.enableBusinessValidation() && businessValidatorFunction != null
                )))
                .resultPath("$.config")
                .build());

        CallAwsService scaleUpAnalysisServer = new CallAwsService(scope, "ScaleUpAnalysisServer", CallAwsServiceProps.builder()
                .service("ecs")
                .action("updateService")
                .iamResources(List.of("*"))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Service", readinessConfig.serviceName(),
                        "DesiredCount", readinessConfig.scaleUpDesiredCount()
                ))
                .resultPath("$.analysisServerScaleUpResult")
                .build());
        scaleUpAnalysisServer.addRetry(ecsRetry());
        scaleUpAnalysisServer.addCatch(markAnalysisServerScaleUpFailure, CatchProps.builder()
                .resultPath("$.error")
                .build());

        LambdaInvoke probeAnalysisServerReady = new LambdaInvoke(scope, "ProbeAnalysisServerReady", LambdaInvokeProps.builder()
                .lambdaFunction(analysisServerProbeFunction)
                .payload(TaskInput.fromObject(Map.of(
                        "url", readinessConfig.readyUrl(),
                        "timeoutSec", readinessConfig.probeTimeoutSeconds(),
                        "expectedReleaseTag", config.expectedReleaseTag(),
                        "requiredChecks", readinessConfig.requiredReadyChecks()
                )))
                .payloadResponseOnly(true)
                .resultPath("$.analysisServer.ready")
                .build());
        probeAnalysisServerReady.addRetry(readinessRetry(readinessConfig.probeIntervalSeconds(), readinessConfig.probeMaxAttempts()));
        probeAnalysisServerReady.addCatch(markAnalysisServerReadyFailure, CatchProps.builder()
                .resultPath("$.error")
                .build());

        LambdaInvoke probeAnalysisServerHealth = new LambdaInvoke(scope, "ProbeAnalysisServerHealth", LambdaInvokeProps.builder()
                .lambdaFunction(analysisServerProbeFunction)
                .payload(TaskInput.fromObject(Map.of(
                        "url", readinessConfig.healthUrl(),
                        "timeoutSec", readinessConfig.probeTimeoutSeconds(),
                        "requiredChecks", List.of()
                )))
                .payloadResponseOnly(true)
                .resultPath("$.analysisServer.health")
                .build());
        probeAnalysisServerHealth.addRetry(readinessRetry(readinessConfig.probeIntervalSeconds(), readinessConfig.probeMaxAttempts()));
        probeAnalysisServerHealth.addCatch(markAnalysisServerHealthFailure, CatchProps.builder()
                .resultPath("$.error")
                .build());

        // Spring Batch는 ECS service를 올리지 않고 one-off RunTask만 실행한다.
        CallAwsService runBatchTask = new CallAwsService(scope, "RunBatchTask", CallAwsServiceProps.builder()
                .service("ecs")
                .action("runTask")
                .iamResources(List.of(resources.clusterArn(), "*"))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "TaskDefinition", workerTaskDefinitionIdentifier,
                        "LaunchType", "FARGATE",
                        "NetworkConfiguration", Map.of(
                                "AwsvpcConfiguration", Map.of(
                                        "Subnets", resources.workerConfig().workerSubnetIds(),
                                        "SecurityGroups", resources.workerConfig().workerSecurityGroupIds(),
                                        "AssignPublicIp", "DISABLED"
                                )
                        ),
                        "Overrides", Map.of(
                                "ContainerOverrides", List.of(Map.of(
                                        "Name", resources.workerConfig().workerContainerName(),
                                        "Environment", workerEnvironmentOverrides(config)
                                ))
                        ),
                        "Tags", List.of(
                                Map.of("Key", "workflow:type", "Value", "analysis-batch"),
                                Map.of("Key", "workflow:runWindow", "Value", config.workerConfig().workerRunWindow()),
                                Map.of("Key", "workflow:executionArn", "Value.$", "$$.Execution.Id")
                        )
                ))
                .resultPath("$.runTaskResult")
                .build());
        runBatchTask.addRetry(ecsRetry());
        runBatchTask.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice checkRunTaskFailures = new Choice(scope, "CheckRunTaskFailures");
        Choice pickWorkerTaskArnPath = new Choice(scope, "PickWorkerTaskArnPath");

        Pass setWorkerTaskArnFromLower = new Pass(scope, "SetWorkerTaskArnFromLower", PassProps.builder()
                .parameters(Map.of("taskArn.$", "$.runTaskResult.tasks[0].taskArn"))
                .resultPath("$.worker")
                .build());

        Pass setWorkerTaskArnFromUpper = new Pass(scope, "SetWorkerTaskArnFromUpper", PassProps.builder()
                .parameters(Map.of("taskArn.$", "$.runTaskResult.Tasks[0].TaskArn"))
                .resultPath("$.worker")
                .build());

        Pass initWorkerPollCounter = counterInit(scope, "InitWorkerPollCounter", "$.workerPoll");

        CallAwsService describeWorkerTask = new CallAwsService(scope, "DescribeWorkerTask", CallAwsServiceProps.builder()
                .service("ecs")
                .action("describeTasks")
                .iamResources(List.of("*"))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Tasks.$", "States.Array($.worker.taskArn)"
                ))
                .resultPath("$.workerDescribeResult")
                .build());
        describeWorkerTask.addRetry(ecsRetry());
        describeWorkerTask.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Wait waitForWorkerStop = waitSeconds(scope, "WaitForWorkerStop", config.workerConfig().workerPollSeconds());
        Pass increaseWorkerPollCounter = counterIncrease(scope, "IncreaseWorkerPollCounter", "$.workerPoll");

        Choice checkWorkerStopped = new Choice(scope, "CheckWorkerStopped");
        Choice checkWorkerExitCode = new Choice(scope, "CheckWorkerExitCode");

        Choice businessValidationNeeded = businessValidatorFunction == null
                ? null
                : new Choice(scope, "BusinessValidationNeeded");
        Choice checkBusinessValidationResult = businessValidatorFunction == null
                ? null
                : new Choice(scope, "CheckBusinessValidationResult");
        Pass initBusinessValidationPollCounter = businessValidatorFunction == null
                ? null
                : counterInit(scope, "InitBusinessValidationPollCounter", "$.businessValidationPoll", 1);
        Wait waitForBusinessValidation = businessValidatorFunction == null
                ? null
                : waitSeconds(scope, "WaitForBusinessValidation", config.businessValidationPollSeconds());
        Pass increaseBusinessValidationPollCounter = businessValidatorFunction == null
                ? null
                : counterIncrease(scope, "IncreaseBusinessValidationPollCounter", "$.businessValidationPoll");

        LambdaInvoke businessValidation = businessValidatorFunction == null ? null : new LambdaInvoke(
                scope,
                "BusinessValidation",
                LambdaInvokeProps.builder()
                        .lambdaFunction(businessValidatorFunction)
                        .payload(TaskInput.fromObject(Map.of(
                                "batchTaskResult.$", "$.workerDescribeResult",
                                "executionInput.$", "$$.Execution.Input",
                                "validation", Map.of(
                                        "enabled", config.enableBusinessValidation(),
                                        "mode", config.businessValidationMode().name(),
                                        "minProcessedCount", config.businessMinProcessedCount(),
                                        "requiredResultFiles", config.businessRequiredResultFiles()
                                )
                        )))
                        .payloadResponseOnly(true)
                        .resultPath("$.businessValidationResult")
                        .build()
        );

        if (businessValidation != null) {
            businessValidation.addRetry(lambdaRetry());
            businessValidation.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());
        }

        CallAwsService scaleDownAnalysisServer = new CallAwsService(scope, "ScaleDownAnalysisServer", CallAwsServiceProps.builder()
                .service("ecs")
                .action("updateService")
                .iamResources(List.of("*"))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Service", readinessConfig.serviceName(),
                        "DesiredCount", readinessConfig.scaleDownDesiredCount()
                ))
                .resultPath("$.analysisServerScaleDownResult")
                .build());
        scaleDownAnalysisServer.addRetry(ecsRetry());
        scaleDownAnalysisServer.addCatch(markAnalysisServerScaleDownFailure, CatchProps.builder()
                .resultPath("$.error")
                .build());

        CallAwsService releaseExecutionLock = new CallAwsService(scope, "ReleaseExecutionLock", CallAwsServiceProps.builder()
                .service("dynamodb")
                .action("deleteItem")
                .iamResources(List.of(lockTableArn))
                .parameters(Map.of(
                        "TableName", lockTableName,
                        "Key", Map.of(
                                "lockKey", Map.of("S", config.dynamoDBConfig().lockKey())
                        ),
                        "ConditionExpression", "ownerExecutionId = :executionId",
                        "ExpressionAttributeValues", Map.of(
                                ":executionId", Map.of("S.$", "$$.Execution.Id")
                        )
                ))
                .resultPath("$.lockReleaseResult")
                .build());
        releaseExecutionLock.addRetry(awsApiRetry());

        Fail releaseLockFailed = new Fail(scope, "ReleaseLockFailed", FailProps.builder()
                .error("ReleaseLockFailed")
                .cause("Workflow lock release failed; manual cleanup may be required.")
                .build());
        releaseExecutionLock.addCatch(releaseLockFailed, CatchProps.builder().resultPath("$.error").build());

        Choice finalizeWorkflow = new Choice(scope, "FinalizeWorkflow");
        Succeed workflowSucceeded = new Succeed(scope, "WorkflowSucceeded");
        Fail workflowFailed = new Fail(scope, "WorkflowFailed", FailProps.builder()
                .error("AnalysisBatchWorkflowFailed")
                .cause("Check execution history and workflow.reason for details.")
                .build());

        acquireExecutionLock.next(setRuntimeFlags);
        setRuntimeFlags.next(scaleUpAnalysisServer);
        scaleUpAnalysisServer.next(probeAnalysisServerReady);
        probeAnalysisServerReady.next(probeAnalysisServerHealth);
        probeAnalysisServerHealth.next(runBatchTask);

        runBatchTask.next(checkRunTaskFailures);
        checkRunTaskFailures
                .when(runTaskHasFailuresCondition(), markRunTaskFailure)
                .otherwise(pickWorkerTaskArnPath);

        pickWorkerTaskArnPath
                .when(Condition.isPresent("$.runTaskResult.tasks[0].taskArn"), setWorkerTaskArnFromLower)
                .when(Condition.isPresent("$.runTaskResult.Tasks[0].TaskArn"), setWorkerTaskArnFromUpper)
                .otherwise(markRunTaskFailure);

        setWorkerTaskArnFromLower.next(initWorkerPollCounter);
        setWorkerTaskArnFromUpper.next(initWorkerPollCounter);

        initWorkerPollCounter.next(describeWorkerTask);
        describeWorkerTask.next(checkWorkerStopped);

        checkWorkerStopped
                .when(workerStoppedCondition(), checkWorkerExitCode)
                .when(Condition.numberGreaterThanEquals("$.workerPoll.attempt", config.workerConfig().workerMaxAttempts()), markWorkerTimeout)
                .otherwise(waitForWorkerStop);

        waitForWorkerStop.next(increaseWorkerPollCounter);
        increaseWorkerPollCounter.next(describeWorkerTask);

        checkWorkerExitCode
                .when(workerExitCodeSuccessCondition(), businessValidatorFunction == null ? markSuccess : businessValidationNeeded)
                .otherwise(markBatchFailure);

        if (businessValidation != null) {
            businessValidationNeeded
                    .when(Condition.booleanEquals("$.config.businessValidationEnabled", true), initBusinessValidationPollCounter)
                    .otherwise(markSuccess);

            initBusinessValidationPollCounter.next(businessValidation);
            businessValidation.next(checkBusinessValidationResult);
            checkBusinessValidationResult
                    .when(businessValidationPassedCondition(), markSuccess)
                    .when(businessValidationFailedCondition(), markBusinessValidationFailure)
                    .when(
                            Condition.and(
                                    businessValidationPendingCondition(),
                                    Condition.numberGreaterThanEquals(
                                            "$.businessValidationPoll.attempt",
                                            config.businessValidationMaxAttempts()
                                    )
                            ),
                            markBusinessValidationTimeout
                    )
                    .when(businessValidationPendingCondition(), waitForBusinessValidation)
                    .otherwise(markBusinessValidationFailure);

            waitForBusinessValidation.next(increaseBusinessValidationPollCounter);
            increaseBusinessValidationPollCounter.next(businessValidation);
        }

        markSuccess.next(scaleDownAnalysisServer);
        markAnalysisServerScaleUpFailure.next(scaleDownAnalysisServer);
        markAnalysisServerReadyFailure.next(scaleDownAnalysisServer);
        markAnalysisServerHealthFailure.next(scaleDownAnalysisServer);
        markRunTaskFailure.next(scaleDownAnalysisServer);
        markWorkerTimeout.next(scaleDownAnalysisServer);
        markBatchFailure.next(scaleDownAnalysisServer);
        if (markBusinessValidationFailure != null) {
            markBusinessValidationFailure.next(scaleDownAnalysisServer);
        }
        if (markBusinessValidationTimeout != null) {
            markBusinessValidationTimeout.next(scaleDownAnalysisServer);
        }
        markFailureFromCatch.next(scaleDownAnalysisServer);

        scaleDownAnalysisServer.next(releaseExecutionLock);
        markAnalysisServerScaleDownFailure.next(releaseExecutionLock);
        releaseExecutionLock.next(finalizeWorkflow);
        finalizeWorkflow
                .when(Condition.stringEquals("$.workflow.status", "SUCCEEDED"), workflowSucceeded)
                .otherwise(workflowFailed);

        return Chain.start(acquireExecutionLock);
    }

    private static RetryProps ecsRetry() {
        return RetryProps.builder()
                .errors(List.of("States.TaskFailed"))
                .interval(Duration.seconds(3))
                .maxAttempts(3)
                .backoffRate(2.0)
                .build();
    }

    private static RetryProps readinessRetry(int intervalSeconds, int maxAttempts) {
        return RetryProps.builder()
                .errors(List.of("States.ALL"))
                .interval(Duration.seconds(intervalSeconds))
                .maxAttempts(maxAttempts)
                .backoffRate(1.0)
                .build();
    }

    private static RetryProps awsApiRetry() {
        return RetryProps.builder()
                .errors(List.of("States.TaskFailed"))
                .interval(Duration.seconds(2))
                .maxAttempts(3)
                .backoffRate(2.0)
                .build();
    }

    private static RetryProps lambdaRetry() {
        return RetryProps.builder()
                .errors(List.of(
                        "States.TaskFailed",
                        "Lambda.ServiceException",
                        "Lambda.AWSLambdaException",
                        "Lambda.SdkClientException"
                ))
                .interval(Duration.seconds(2))
                .maxAttempts(2)
                .backoffRate(2.0)
                .build();
    }

    private static Pass counterInit(Construct scope, String id, String resultPath) {
        return counterInit(scope, id, resultPath, 0);
    }

    private static Pass counterInit(Construct scope, String id, String resultPath, int initialAttempt) {
        return new Pass(scope, id, PassProps.builder()
                .result(Result.fromObject(Map.of("attempt", initialAttempt)))
                .resultPath(resultPath)
                .build());
    }

    private static Pass counterIncrease(Construct scope, String id, String resultPath) {
        return new Pass(scope, id, PassProps.builder()
                .parameters(Map.of("attempt.$", "States.MathAdd(" + resultPath + ".attempt, 1)"))
                .resultPath(resultPath)
                .build());
    }

    private static Wait waitSeconds(Construct scope, String id, int seconds) {
        return new Wait(scope, id, WaitProps.builder()
                .time(WaitTime.duration(Duration.seconds(seconds)))
                .build());
    }

    private static Pass marker(Construct scope, String id, String reason) {
        return new Pass(scope, id, PassProps.builder()
                .result(Result.fromObject(Map.of(
                        "status", "FAILED",
                        "reason", reason
                )))
                .resultPath("$.workflow")
                .build());
    }

    private static Pass successMarker(Construct scope) {
        return new Pass(scope, "MarkSuccess", PassProps.builder()
                .result(Result.fromObject(Map.of("status", "SUCCEEDED")))
                .resultPath("$.workflow")
                .build());
    }

    private static Condition runTaskHasFailuresCondition() {
        return Condition.or(
                Condition.isPresent("$.runTaskResult.failures[0]"),
                Condition.isPresent("$.runTaskResult.Failures[0]")
        );
    }

    private static Condition workerStoppedCondition() {
        Condition lower = Condition.and(
                Condition.isPresent("$.workerDescribeResult.tasks[0].lastStatus"),
                Condition.stringEquals("$.workerDescribeResult.tasks[0].lastStatus", "STOPPED")
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.workerDescribeResult.Tasks[0].LastStatus"),
                Condition.stringEquals("$.workerDescribeResult.Tasks[0].LastStatus", "STOPPED")
        );

        return Condition.or(lower, upper);
    }

    private static Condition workerExitCodeSuccessCondition() {
        Condition lower = Condition.and(
                Condition.isPresent("$.workerDescribeResult.tasks[0].containers[0].exitCode"),
                Condition.numberEquals("$.workerDescribeResult.tasks[0].containers[0].exitCode", 0)
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.workerDescribeResult.Tasks[0].Containers[0].ExitCode"),
                Condition.numberEquals("$.workerDescribeResult.Tasks[0].Containers[0].ExitCode", 0)
        );

        return Condition.or(lower, upper);
    }

    private static Condition businessValidationPassedCondition() {
        return Condition.or(
                Condition.stringEquals("$.businessValidationResult.status", "PASS"),
                Condition.stringEquals("$.businessValidationResult.status", "SKIP")
        );
    }

    private static Condition businessValidationPendingCondition() {
        return Condition.stringEquals("$.businessValidationResult.status", "PENDING");
    }

    private static Condition businessValidationFailedCondition() {
        return Condition.stringEquals("$.businessValidationResult.status", "FAIL");
    }

    private static List<Object> workerEnvironmentOverrides(OnDemandWorkflowConfig config) {
        WorkerConfig workerConfig = config.workerConfig();
        List<Object> env = new ArrayList<>();

        // 현재 worker 이미지는 entrypoint가 CLI 인자를 전달하지 않으므로
        // Spring Batch 실행에 필요한 설정은 env override만 사용한다.
        env.add(Map.of("Name", "SPRING_PROFILES_ACTIVE", "Value", workerConfig.workerSpringProfile()));
        env.add(Map.of("Name", "SPRING_BATCH_JOB_NAME", "Value", workerConfig.workerBatchJobName()));
        env.add(Map.of("Name", "DB_POOL_MAX", "Value", "5"));
        env.add(Map.of("Name", "DB_POOL_MIN", "Value", "1"));

        // worker는 application.kafka.yml에서 MSK_BOOTSTRAP_SERVERS만 읽고 보안 설정은 코드/설정 파일에 이미 고정돼 있다.
        env.add(Map.of("Name", "MSK_BOOTSTRAP_SERVERS", "Value", workerConfig.workerMskBootstrapServers()));

        env.add(Map.of("Name", "WORKFLOW_RUN_WINDOW", "Value", workerConfig.workerRunWindow()));
        env.add(Map.of("Name", "WORKFLOW_LOCK_KEY", "Value", config.dynamoDBConfig().lockKey()));
        env.add(Map.of("Name", "WORKFLOW_EXECUTION_ID", "Value.$", "$$.Execution.Id"));
        env.add(Map.of("Name", "WORKFLOW_RUN_ID", "Value.$", "$$.Execution.Id"));

        if (config.hasExpectedReleaseTag()) {
            env.add(Map.of("Name", "WORKFLOW_EXPECTED_RELEASE", "Value", config.expectedReleaseTag()));
        }
        if (workerConfig.workerInputBasePath() != null && !workerConfig.workerInputBasePath().isBlank()) {
            env.add(Map.of("Name", "WORKFLOW_INPUT_PATH", "Value.$", statesFormatPath(workerConfig.workerInputBasePath())));
        }
        if (workerConfig.workerOutputBasePath() != null && !workerConfig.workerOutputBasePath().isBlank()) {
            env.add(Map.of("Name", "WORKFLOW_OUTPUT_PATH", "Value.$", statesFormatPath(workerConfig.workerOutputBasePath())));
        }
        if (workerConfig.workerLockBasePath() != null && !workerConfig.workerLockBasePath().isBlank()) {
            env.add(Map.of("Name", "WORKFLOW_LOCK_PATH", "Value.$", statesFormatPath(workerConfig.workerLockBasePath())));
        }

        return env;
    }
    private static String statesFormatPath(String basePath) {
        String escaped = basePath == null ? "" : basePath.replace("'", "''");
        return "States.Format('{}/{}', '" + escaped + "', $$.Execution.Id)";
    }
}
