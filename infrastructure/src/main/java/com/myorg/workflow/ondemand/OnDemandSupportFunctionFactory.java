package com.myorg.workflow.ondemand;

import com.myorg.config.OnDemandWorkflowConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

/**
 * 온디맨드 워크플로우 보조 Lambda 생성 팩토리
 * 생성 대상:
 * - Runtime Guard: RDS 격리/보안 가드
 * - Business Validator: 배치 후 비즈니스 성공 조건 검증
 * - Watchdog: 비정상 종료 후 FastAPI desiredCount 자동 복구
 */
public final class OnDemandSupportFunctionFactory {
    private OnDemandSupportFunctionFactory() {
    }

    public static Function createRuntimeGuard(
            Construct scope,
            String id,
            OnDemandWorkflowConfig config
    ) {
        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.watchDogConfig().watchdogLambdaAssetPath()))
                .timeout(Duration.seconds(30))
                .memorySize(config.watchDogConfig().watchdogMemoryMb())
                .build());
    }

    public static Function createBusinessValidator(
            Construct scope,
            String id,
            OnDemandWorkflowConfig config
    ) {
        // Business validator는 입력 payload 기반 검증만 수행하
        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.lambdaConfig().businessValidatorLambdaAssetPath()))
                .timeout(Duration.seconds(30))
                .memorySize(config.lambdaConfig().businessValidatorMemoryMb())
                .build());
    }

    public static Function createWatchdog(
            Construct scope,
            String id,
            OnDemandWorkflowConfig config
    ) {
        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.watchDogConfig().watchdogLambdaAssetPath()))
                .timeout(Duration.seconds(30))
                .memorySize(config.watchDogConfig().watchdogMemoryMb())
                .build());
    }
}
