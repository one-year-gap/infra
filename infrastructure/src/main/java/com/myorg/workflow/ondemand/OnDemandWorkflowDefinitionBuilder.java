package com.myorg.workflow.ondemand;

import com.myorg.config.OnDemandWorkflowConfig;
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
import java.util.Objects;

/**
 * 온디맨드 워크플로우 Step Functions 정의 빌더.
 * - P0: 릴리즈 태그 정합성 검증 / 동시 실행 락 / 강제 종료 복구 연계(Watchdog)
 * - P1: /ready 계약 강화 / 비즈니스 검증 단계 / worker override 주입 / 격리 가드
 * - P2: 실행 중 배포 충돌 감지
 */
public class OnDemandWorkflowDefinitionBuilder {

    /**
     * 워크플로우 상태 전이를 생성한다.
     * 순서 보장:
     * 1) 락 획득
     * 2) baseline/release/격리 검증
     * 3) FastAPI scale-up + ready gate
     * 4) Worker 실행 + 종료/검증
     * 5) 성공/실패 공통 scale-down + lock release
     */
    public Chain build(
            Construct scope,
            OnDemandWorkflowResources resources,
            OnDemandWorkflowConfig config,
            IFunction readyProbeFunction,
            IFunction runtimeGuardFunction,
            IFunction businessValidatorFunction,
            String lockTableName,
            String lockTableArn
    ) {
        // -----------------------------------------------------------------
        // 공통 마커
        // -----------------------------------------------------------------
        Pass markFailureFromCatch = new Pass(scope, "MarkFailureFromCatch", PassProps.builder()
                .parameters(Map.of(
                        "status", "FAILED",
                        "reason", "TASK_EXCEPTION",
                        "error.$", "$.error.Error",
                        "cause.$", "$.error.Cause"
                ))
                .resultPath("$.workflow")
                .build());

        Pass markFastApiServiceNotFound = marker(scope, "MarkFastApiServiceNotFound", "FASTAPI_SERVICE_NOT_FOUND");
        Pass markReleaseMismatch = marker(scope, "MarkReleaseMismatch", "RELEASE_TAG_MISMATCH");
        Pass markSecurityViolation = marker(scope, "MarkSecurityViolation", "ISOLATION_GUARD_FAILED");
        Pass markSteadyTimeout = marker(scope, "MarkSteadyTimeout", "FASTAPI_STEADY_TIMEOUT");
        Pass markAlbHealthTimeout = marker(scope, "MarkAlbHealthTimeout", "ALB_HEALTH_TIMEOUT");
        Pass markDeploymentRace = marker(scope, "MarkDeploymentRace", "PINNED_TASK_DEFINITION_DRIFT");
        Pass markRunTaskFailure = marker(scope, "MarkRunTaskFailure", "WORKER_RUN_TASK_FAILED");
        Pass markWorkerTimeout = marker(scope, "MarkWorkerTimeout", "WORKER_TASK_TIMEOUT");
        Pass markBatchFailure = marker(scope, "MarkBatchFailure", "BATCH_EXIT_CODE_NON_ZERO");
        Pass markBusinessValidationFailure = marker(scope, "MarkBusinessValidationFailure", "BUSINESS_VALIDATION_FAILED");
        Pass markScaleDownFailure = marker(scope, "MarkScaleDownFailure", "SCALE_DOWN_FAILED");
        Pass markSuccess = successMarker(scope);

        // -----------------------------------------------------------------
        // 0) 동시 실행 락 (P0)
        // -----------------------------------------------------------------
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
                .cause("Another workflow execution already holds the lock.")
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

        // 실행 플래그 -> 상태 데이터에 저장
        Pass setRuntimeFlags = new Pass(scope, "SetRuntimeFlags", PassProps.builder()
                .result(Result.fromObject(Map.of(
                        "releaseCheckEnabled", config.enableReleaseCheck() && config.hasExpectedReleaseTag(),
                        "isolationGuardEnabled", config.enforceIsolationGuard(),
                        "businessValidationEnabled", config.enableBusinessValidation()
                )))
                .resultPath("$.config")
                .build());

        // -----------------------------------------------------------------
        // 1) FastAPI baseline 조회 + revision pin (P0/P2)
        // -----------------------------------------------------------------
        CallAwsService describeServiceForBaseline = new CallAwsService(scope, "DescribeServiceForBaseline", CallAwsServiceProps.builder()
                .service("ecs")
                .action("describeServices")
                .iamResources(List.of(resources.clusterArn(), resources.fastApiServiceArn()))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Services", List.of(resources.fastApiServiceName())
                ))
                .resultPath("$.baselineService")
                .build());
        describeServiceForBaseline.addRetry(ecsRetry());
        describeServiceForBaseline.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice pickPinnedTaskDefinitionPath = new Choice(scope, "PickPinnedTaskDefinitionPath");
        Pass setPinnedTaskDefFromLower = new Pass(scope, "SetPinnedTaskDefFromLower", PassProps.builder()
                .parameters(Map.of("taskDefinitionArn.$", "$.baselineService.services[0].taskDefinition"))
                .resultPath("$.pinnedFastApi")
                .build());

        // ECS SDK 응답 케이스
        Pass setPinnedTaskDefFromUpper = new Pass(scope, "SetPinnedTaskDefFromUpper", PassProps.builder()
                .parameters(Map.of("taskDefinitionArn.$", "$.baselineService.Services[0].TaskDefinition"))
                .resultPath("$.pinnedFastApi")
                .build());

        // -----------------------------------------------------------------
        // 2) taskDefinition 상세 조회 + 릴리즈 검증 (P0)
        // -----------------------------------------------------------------
        CallAwsService describeTaskDefinitionForPinned = new CallAwsService(scope, "DescribeTaskDefinitionForPinned",
                CallAwsServiceProps.builder()
                        .service("ecs")
                        .action("describeTaskDefinition")
                        // taskDefinition ARN이 상태 데이터에서 동적으로 들어오므로 와일드카드 사용.
                        .iamResources(List.of("*"))
                        .parameters(Map.of(
                                "TaskDefinition.$", "$.pinnedFastApi.taskDefinitionArn"
                        ))
                        .resultPath("$.pinnedFastApiTaskDef")
                        .build());
        describeTaskDefinitionForPinned.addRetry(ecsRetry());
        describeTaskDefinitionForPinned.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice releaseCheckNeeded = new Choice(scope, "ReleaseCheckNeeded");
        Choice checkReleaseTagMatch = new Choice(scope, "CheckReleaseTagMatch");

        // -----------------------------------------------------------------
        // 3) 격리 가드 (P1)
        // -----------------------------------------------------------------
        Choice isolationGuardNeeded = new Choice(scope, "IsolationGuardNeeded");
        LambdaInvoke runtimeGuard = new LambdaInvoke(scope, "RuntimeIsolationGuard", LambdaInvokeProps.builder()
                .lambdaFunction(runtimeGuardFunction)
                .payload(TaskInput.fromObject(Map.of(
                        "fastApiTaskDefinitionArn.$", "$.pinnedFastApi.taskDefinitionArn",
                        "workerTaskDefinitionArn", resources.workerConfig().workerTaskDefinitionArn(),
                        "fastApiSecurityGroupId", resources.fastApiSecurityGroupId(),
                        "rdsSecurityGroupId", resources.rdsSecurityGroupId(),
                        "rdsSecretArn", resources.rdsSecretArn(),
                        "enforceSeparateEfsAccessPoints", config. enforceSeparateEfsAccessPoints(),
                        "fastApiEfsAccessPointId", resources.fastApiEfsAccessPointId(),
                        "workerEfsAccessPointId", resources.workerEfsAccessPointId(),
                        "disallowedEnvNames", resources.isolationDisallowedEnvNames()
                )))
                .payloadResponseOnly(true)
                .resultPath("$.guardResult")
                .build());

        runtimeGuard.addRetry(lambdaRetry());
        runtimeGuard.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice runtimeGuardPassed = new Choice(scope, "RuntimeGuardPassed");

        // -----------------------------------------------------------------
        // 4) FastAPI scale-up (revision pin 포함) + steady 루프
        // -----------------------------------------------------------------
        CallAwsService scaleUpFastApi = new CallAwsService(scope, "ScaleUpFastApi", CallAwsServiceProps.builder()
                .service("ecs")
                .action("updateService")
                .iamResources(List.of(resources.clusterArn(), resources.fastApiServiceArn()))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Service", resources.fastApiServiceName(),
                        "DesiredCount", config.scaleUpDesiredCount(),
                        "TaskDefinition.$", "$.pinnedFastApi.taskDefinitionArn"
                ))
                .resultPath("$.scaleUpResult")
                .build());

        scaleUpFastApi.addRetry(ecsRetry());
        scaleUpFastApi.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Pass initSteadyCounter = counterInit(scope, "InitSteadyCounter", "$.steady");
        CallAwsService describeFastApiService = ecsDescribeService(scope, "DescribeFastApiService", resources, "$.describeServiceResult");
        describeFastApiService.addRetry(ecsRetry());
        describeFastApiService.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Wait waitForSteady = waitSeconds(scope, "WaitForSteady", config.steadyPollSeconds());
        Pass increaseSteadyCounter = counterIncrease(scope, "IncreaseSteadyCounter", "$.steady");
        Choice checkServiceSteady = new Choice(scope, "CheckServiceSteady");

        // -----------------------------------------------------------------
        // 5) ALB healthy 루프
        // -----------------------------------------------------------------
        Pass initAlbHealthCounter = counterInit(scope, "InitAlbHealthCounter", "$.albHealth");
        CallAwsService describeTargetHealth = elbDescribeTargetHealth(scope, resources, "$.albHealthResult");
        describeTargetHealth.addRetry(elbRetry());
        describeTargetHealth.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Wait waitForAlbHealth = waitSeconds(scope, "WaitForAlbHealth", config.albHealthPollSeconds());
        Pass increaseAlbHealthCounter = counterIncrease(scope, "IncreaseAlbHealthCounter", "$.albHealth");
        Choice checkAlbTargetHealthy = new Choice(scope, "CheckAlbTargetHealthy");

        // -----------------------------------------------------------------
        // 6) Ready gate (P1)
        // -----------------------------------------------------------------
        LambdaInvoke readyGate = new LambdaInvoke(scope, "ReadyGate", LambdaInvokeProps.builder()
                .lambdaFunction(readyProbeFunction)
                .payload(TaskInput.fromObject(Map.of(
                        "url", resources.readyUrl(),
                        "timeoutSec", config.readyProbeTimeoutSeconds(),
                        "expectedReleaseTag", config.expectedReleaseTag(),
                        "requiredChecks", config.readyRequiredChecks()
                )))
                .payloadResponseOnly(true)
                .resultPath("$.readyResult")
                .build());

        readyGate.addRetry(RetryProps.builder()
                .errors(List.of(
                        "States.TaskFailed",
                        "Lambda.ServiceException",
                        "Lambda.AWSLambdaException",
                        "Lambda.SdkClientException"
                ))
                .interval(Duration.seconds(config.readyRetryIntervalSeconds()))
                .maxAttempts(config.readyMaxAttempts())
                .backoffRate(config.readyBackoffRate())
                .build());
        readyGate.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        // 실행 직전에 baseline pin과 현재 서비스 revision이 같은지 다시 검사
        CallAwsService describeServiceBeforeBatch = ecsDescribeService(scope, "DescribeServiceBeforeBatch", resources, "$.preBatchService");
        describeServiceBeforeBatch.addRetry(ecsRetry());
        describeServiceBeforeBatch.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice checkPinnedTaskDefinitionStillSame = new Choice(scope, "CheckPinnedTaskDefinitionStillSame");


        // -----------------------------------------------------------------
        // 7) Worker RunTask + 파라미터 주입 (P1)
        // -----------------------------------------------------------------
        CallAwsService runWorkerTask = new CallAwsService(scope, "RunWorkerTask", CallAwsServiceProps.builder()
                .service("ecs")
                .action("runTask")
                .iamResources(List.of(resources.clusterArn(), resources.workerConfig().workerTaskDefinitionArn(), "*"))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "TaskDefinition", resources.workerConfig().workerTaskDefinitionArn(),
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
                                Map.of("Key", "workflow:runWindow", "Value", config.workerConfig().workerRunWindow()),
                                Map.of("Key", "workflow:executionArn", "Value.$", "$$.Execution.Id")
                        )
                ))
                .resultPath("$.runTaskResult")
                .build());

        runWorkerTask.addRetry(ecsRetry());
        runWorkerTask.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

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

        // -----------------------------------------------------------------
        // 8) Worker 종료 대기 + exit code 판정 + 비즈니스 검증 (P1)
        // -----------------------------------------------------------------
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

        Choice businessValidationNeeded = new Choice(scope, "BusinessValidationNeeded");
        LambdaInvoke businessValidation = new LambdaInvoke(scope, "BusinessValidation", LambdaInvokeProps.builder()
                .lambdaFunction(businessValidatorFunction)
                .payload(TaskInput.fromObject(Map.of(
                        "batchTaskResult.$", "$.workerDescribeResult",
                        "executionInput.$", "$$.Execution.Input",
                        "validation", Map.of(
                                "enabled", config.enableBusinessValidation(),
                                "minProcessedCount", config.businessMinProcessedCount(),
                                "requiredResultFiles", config.businessRequiredResultFiles()
                        )
                )))
                .payloadResponseOnly(true)
                .resultPath("$.businessValidationResult")
                .build());
        businessValidation.addRetry(lambdaRetry());
        businessValidation.addCatch(markFailureFromCatch, CatchProps.builder().resultPath("$.error").build());

        Choice checkBusinessValidationResult = new Choice(scope, "CheckBusinessValidationResult");

        // -----------------------------------------------------------------
        // 9) scale-down + lock release + 최종 상태 결정
        // -----------------------------------------------------------------
        CallAwsService scaleDownFastApi = new CallAwsService(scope, "ScaleDownFastApi", CallAwsServiceProps.builder()
                .service("ecs")
                .action("updateService")
                .iamResources(List.of(resources.clusterArn(), resources.fastApiServiceArn()))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Service", resources.fastApiServiceName(),
                        "DesiredCount", config.scaleDownDesiredCount()
                ))
                .resultPath("$.scaleDownResult")
                .build());
        scaleDownFastApi.addRetry(ecsRetry());
        scaleDownFastApi.addCatch(markScaleDownFailure, CatchProps.builder().resultPath("$.error").build());

        CallAwsService releaseExecutionLock = new CallAwsService(scope, "ReleaseExecutionLock", CallAwsServiceProps.builder()
                .service("dynamodb")
                .action("deleteItem")
                .iamResources(List.of(lockTableArn))
                .parameters(Map.of(
                        "TableName", lockTableName,
                        "Key", Map.of(
                                "lockKey", Map.of("S", config.dynamoDBConfig().lockKey())
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
                .error("OnDemandWorkflowFailed")
                .cause("Check execution history and workflow.reason for details.")
                .build());

        // -----------------------------------------------------------------
        // 전이 연결
        // -----------------------------------------------------------------
        acquireExecutionLock.next(setRuntimeFlags);
        setRuntimeFlags.next(describeServiceForBaseline);
        describeServiceForBaseline.next(pickPinnedTaskDefinitionPath);

        pickPinnedTaskDefinitionPath
                .when(Condition.isPresent("$.baselineService.services[0].taskDefinition"), setPinnedTaskDefFromLower)
                .when(Condition.isPresent("$.baselineService.Services[0].TaskDefinition"), setPinnedTaskDefFromUpper)
                .otherwise(markFastApiServiceNotFound);

        setPinnedTaskDefFromLower.next(describeTaskDefinitionForPinned);
        setPinnedTaskDefFromUpper.next(describeTaskDefinitionForPinned);
        describeTaskDefinitionForPinned.next(releaseCheckNeeded);

        releaseCheckNeeded
                .when(Condition.booleanEquals("$.config.releaseCheckEnabled", true), checkReleaseTagMatch)
                .otherwise(isolationGuardNeeded);

        checkReleaseTagMatch
                .when(releaseTagMatchedCondition(config.expectedReleaseTag(), config.fastApiImageTagPrefix()), isolationGuardNeeded)
                .otherwise(markReleaseMismatch);

        isolationGuardNeeded
                .when(Condition.booleanEquals("$.config.isolationGuardEnabled", true), runtimeGuard)
                .otherwise(scaleUpFastApi);

        runtimeGuard.next(runtimeGuardPassed);
        runtimeGuardPassed
                .when(Condition.stringEquals("$.guardResult.status", "PASS"), scaleUpFastApi)
                .otherwise(markSecurityViolation);

        scaleUpFastApi.next(initSteadyCounter);
        initSteadyCounter.next(describeFastApiService);
        describeFastApiService.next(checkServiceSteady);

        checkServiceSteady
                .when(serviceSteadyCondition(config.scaleUpDesiredCount()), initAlbHealthCounter)
                .when(Condition.numberGreaterThanEquals("$.steady.attempt", config.steadyMaxAttempts()), markSteadyTimeout)
                .otherwise(waitForSteady);

        waitForSteady.next(increaseSteadyCounter);
        increaseSteadyCounter.next(describeFastApiService);

        initAlbHealthCounter.next(describeTargetHealth);
        describeTargetHealth.next(checkAlbTargetHealthy);

        checkAlbTargetHealthy
                .when(albHealthyCondition(), readyGate)
                .when(Condition.numberGreaterThanEquals("$.albHealth.attempt", config.albHealthMaxAttempts()), markAlbHealthTimeout)
                .otherwise(waitForAlbHealth);

        waitForAlbHealth.next(increaseAlbHealthCounter);
        increaseAlbHealthCounter.next(describeTargetHealth);

        readyGate.next(describeServiceBeforeBatch);
        describeServiceBeforeBatch.next(checkPinnedTaskDefinitionStillSame);

        checkPinnedTaskDefinitionStillSame
                .when(pinnedTaskDefinitionStillSameCondition(), runWorkerTask)
                .otherwise(markDeploymentRace);

        runWorkerTask.next(checkRunTaskFailures);
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
                .when(workerExitCodeSuccessCondition(), businessValidationNeeded)
                .otherwise(markBatchFailure);

        businessValidationNeeded
                .when(Condition.booleanEquals("$.config.businessValidationEnabled", true), businessValidation)
                .otherwise(markSuccess);

        businessValidation.next(checkBusinessValidationResult);
        checkBusinessValidationResult
                .when(businessValidationPassedCondition(), markSuccess)
                .otherwise(markBusinessValidationFailure);

        // 성공/실패/검증실패 공통 cleanup 경로로 합류.
        markSuccess.next(scaleDownFastApi);
        markFastApiServiceNotFound.next(scaleDownFastApi);
        markReleaseMismatch.next(scaleDownFastApi);
        markSecurityViolation.next(scaleDownFastApi);
        markSteadyTimeout.next(scaleDownFastApi);
        markAlbHealthTimeout.next(scaleDownFastApi);
        markDeploymentRace.next(scaleDownFastApi);
        markRunTaskFailure.next(scaleDownFastApi);
        markWorkerTimeout.next(scaleDownFastApi);
        markBatchFailure.next(scaleDownFastApi);
        markBusinessValidationFailure.next(scaleDownFastApi);
        markFailureFromCatch.next(scaleDownFastApi);

        // lock은 반드시 해제
        scaleDownFastApi.next(releaseExecutionLock);
        markScaleDownFailure.next(releaseExecutionLock);

        releaseExecutionLock.next(finalizeWorkflow);
        finalizeWorkflow
                .when(Condition.stringEquals("$.workflow.status", "SUCCEEDED"), workflowSucceeded)
                .otherwise(workflowFailed);

        return Chain.start(acquireExecutionLock);
    }

    private static CallAwsService ecsDescribeService(
            Construct scope,
            String id,
            OnDemandWorkflowResources resources,
            String resultPath
    ) {
        // 서비스 상태 조회 공통 템플릿.
        return new CallAwsService(scope, id, CallAwsServiceProps.builder()
                .service("ecs")
                .action("describeServices")
                .iamResources(List.of(resources.clusterArn(), resources.fastApiServiceArn()))
                .parameters(Map.of(
                        "Cluster", resources.clusterArn(),
                        "Services", List.of(resources.fastApiServiceName())
                ))
                .resultPath(resultPath)
                .build());
    }

    private static CallAwsService elbDescribeTargetHealth(
            Construct scope,
            OnDemandWorkflowResources resources,
            String resultPath
    ) {
        // TargetGroup 상태 확인.
        return new CallAwsService(scope, "DescribeTargetHealth", CallAwsServiceProps.builder()
                .service("elasticloadbalancingv2")
                .action("describeTargetHealth")
                .iamResources(List.of(resources.fastApiTargetGroupArn()))
                .parameters(Map.of("TargetGroupArn", resources.fastApiTargetGroupArn()))
                .resultPath(resultPath)
                .build());
    }

    private static Pass counterInit(Construct scope, String id, String resultPath) {
        // 폴링 루프 초기 카운터
        return new Pass(scope, id, PassProps.builder()
                .result(Result.fromObject(Map.of("attempt", 0)))
                .resultPath(resultPath)
                .build());
    }

    private static Pass counterIncrease(Construct scope, String id, String resultPath) {
        // Step Functions intrinsic function으로 카운터 +1.
        return new Pass(scope, id, PassProps.builder()
                .parameters(Map.of("attempt.$", "States.MathAdd(" + resultPath + ".attempt, 1)"))
                .resultPath(resultPath)
                .build());
    }

    private static Wait waitSeconds(Construct scope, String id, int seconds) {
        // API 폴링 간격 제어.
        return new Wait(scope, id, WaitProps.builder()
                .time(WaitTime.duration(Duration.seconds(seconds)))
                .build());
    }

    private static Pass marker(Construct scope, String id, String reason) {
        // 실패 사유를 표준 포맷으로 저장.
        return new Pass(scope, id, PassProps.builder()
                .result(Result.fromObject(Map.of(
                        "status", "FAILED",
                        "reason", reason
                )))
                .resultPath("$.workflow")
                .build());
    }

    private static Pass successMarker(Construct scope) {
        // 최종 성공 마커
        return new Pass(scope, "MarkSuccess", PassProps.builder()
                .result(Result.fromObject(Map.of("status", "SUCCEEDED")))
                .resultPath("$.workflow")
                .build());
    }

    private static RetryProps ecsRetry() {
        // ECS 계열 API 재시도
        return RetryProps.builder()
                .errors(List.of("States.TaskFailed"))
                .interval(Duration.seconds(3))
                .maxAttempts(3)
                .backoffRate(2.0)
                .build();
    }

    private static RetryProps elbRetry() {
        // ELB 계열 API 재시도
        return RetryProps.builder()
                .errors(List.of("States.TaskFailed"))
                .interval(Duration.seconds(2))
                .maxAttempts(3)
                .backoffRate(2.0)
                .build();
    }

    private static RetryProps awsApiRetry() {
        // 범용 AWS API(DynamoDB 등) 재시도
        return RetryProps.builder()
                .errors(List.of("States.TaskFailed"))
                .interval(Duration.seconds(2))
                .maxAttempts(3)
                .backoffRate(2.0)
                .build();
    }

    private static RetryProps lambdaRetry() {
        // Lambda invoke 재시도
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

    private static Condition serviceSteadyCondition(int expectedRunning) {
        // steady 판단:
        Condition lower = Condition.and(
                Condition.isPresent("$.describeServiceResult.services[0]"),
                Condition.numberEquals("$.describeServiceResult.services[0].runningCount", expectedRunning),
                Condition.numberEquals("$.describeServiceResult.services[0].desiredCount", expectedRunning),
                Condition.numberEquals("$.describeServiceResult.services[0].pendingCount", 0)
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.describeServiceResult.Services[0]"),
                Condition.numberEquals("$.describeServiceResult.Services[0].RunningCount", expectedRunning),
                Condition.numberEquals("$.describeServiceResult.Services[0].DesiredCount", expectedRunning),
                Condition.numberEquals("$.describeServiceResult.Services[0].PendingCount", 0)
        );

        return Condition.or(lower, upper);
    }

    private static Condition albHealthyCondition() {
        // ALB가 healthy를 반환해야만 Ready gate로 진입.
        Condition lower = Condition.and(
                Condition.isPresent("$.albHealthResult.targetHealthDescriptions[0].targetHealth.state"),
                Condition.stringEquals("$.albHealthResult.targetHealthDescriptions[0].targetHealth.state", "healthy")
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.albHealthResult.TargetHealthDescriptions[0].TargetHealth.State"),
                Condition.stringEquals("$.albHealthResult.TargetHealthDescriptions[0].TargetHealth.State", "healthy")
        );

        return Condition.or(lower, upper);
    }

    private static Condition releaseTagMatchedCondition(String expectedReleaseTag, String imageTagPrefix) {
        if (expectedReleaseTag == null || expectedReleaseTag.isBlank()) {
            return Condition.booleanEquals("$.config.releaseCheckEnabled", false);
        }

        String imageTag = imageTagPrefix + expectedReleaseTag;
        String imageTagPattern = "*:" + imageTag + "*";

        Condition lower = Condition.and(
                Condition.isPresent("$.pinnedFastApiTaskDef.taskDefinition.containerDefinitions[0].image"),
                Condition.stringMatches("$.pinnedFastApiTaskDef.taskDefinition.containerDefinitions[0].image", imageTagPattern)
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.pinnedFastApiTaskDef.TaskDefinition.ContainerDefinitions[0].Image"),
                Condition.stringMatches("$.pinnedFastApiTaskDef.TaskDefinition.ContainerDefinitions[0].Image", imageTagPattern)
        );

        return Condition.or(lower, upper);
    }

    private static Condition pinnedTaskDefinitionStillSameCondition() {
        Condition lower = Condition.and(
                Condition.isPresent("$.preBatchService.services[0].taskDefinition"),
                Condition.stringEqualsJsonPath(
                        "$.preBatchService.services[0].taskDefinition",
                        "$.pinnedFastApi.taskDefinitionArn"
                )
        );

        Condition upper = Condition.and(
                Condition.isPresent("$.preBatchService.Services[0].TaskDefinition"),
                Condition.stringEqualsJsonPath(
                        "$.preBatchService.Services[0].TaskDefinition",
                        "$.pinnedFastApi.taskDefinitionArn"
                )
        );

        return Condition.or(lower, upper);
    }

    private static Condition runTaskHasFailuresCondition() {
        return Condition.or(
                Condition.isPresent("$.runTaskResult.failures[0]"),
                Condition.isPresent("$.runTaskResult.Failures[0]")
        );
    }

    private static Condition workerStoppedCondition() {
        // worker task가 STOPPED 상태가 될 때까지 폴링
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

    private static List<Object> workerEnvironmentOverrides(OnDemandWorkflowConfig config) {
        List<Object> env = new ArrayList<>();

        env.add(Map.of("Name", "SPRING_BATCH_JOB_NAME", "Value", config.workerConfig().workerBatchJobName()));
        env.add(Map.of("Name", "WORKFLOW_RUN_WINDOW", "Value", config.workerConfig().workerRunWindow()));
        env.add(Map.of("Name", "WORKFLOW_EXPECTED_RELEASE", "Value", config.expectedReleaseTag()));


        env.add(Map.of("Name", "WORKFLOW_EXECUTION_ID", "Value.$", "$$.Execution.Id"));
        env.add(Map.of("Name", "WORKFLOW_RUN_ID", "Value.$", "$$.Execution.Id"));


        env.add(Map.of("Name", "WORKFLOW_INPUT_PATH", "Value.$", statesFormatPath(config.workerConfig().workerInputBasePath())));
        env.add(Map.of("Name", "WORKFLOW_OUTPUT_PATH", "Value.$", statesFormatPath(config.workerConfig().workerOutputBasePath())));
        env.add(Map.of("Name", "WORKFLOW_LOCK_PATH", "Value.$", statesFormatPath(config.workerConfig().workerLockBasePath())));
        return env;
    }

    private static String statesFormatPath(String basePath) {
        // runId별 경로 분리
        String escaped = basePath == null ? "" : basePath.replace("'", "''");
        return "States.Format('{}/{}', '" + escaped + "', $$.Execution.Id)";
    }
}
