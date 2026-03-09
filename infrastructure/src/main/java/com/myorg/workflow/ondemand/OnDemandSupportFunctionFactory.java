package com.myorg.workflow.ondemand;

import com.myorg.config.OnDemandWorkflowConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

/**
 * analysis batch 워크플로우 보조 Lambda 생성 팩토리.
 * 현재는 배치 종료 후 비즈니스 성공 조건을 검증하는 Lambda만 사용한다.
 */
public final class OnDemandSupportFunctionFactory {
    private OnDemandSupportFunctionFactory() {
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
}
