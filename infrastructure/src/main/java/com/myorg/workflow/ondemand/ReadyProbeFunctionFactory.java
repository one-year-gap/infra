package com.myorg.workflow.ondemand;

import com.myorg.config.OnDemandWorkflowConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;

/**
 * /ready 게이트 Lambda 생성
 * - Step Functions 정의와 HTTP 검증 로직을 분리
 */
public final class ReadyProbeFunctionFactory {
    private ReadyProbeFunctionFactory() {
    }

    public static Function create(
            Construct scope,
            String id,
            OnDemandWorkflowResources resources,
            OnDemandWorkflowConfig config
    ) {
        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.lambdaConfig().readyProbeLambdaAssetPath()))
                .timeout(Duration.seconds(config.readyProbeTimeoutSeconds()))
                .memorySize(config.lambdaConfig().readyProbeMemoryMb())
                .vpc(resources.readyProbeVpc())
                .securityGroups(List.of(resources.readyProbeSecurityGroup()))
                .vpcSubnets(resources.readyProbeSubnets())
                .build());
    }
}
