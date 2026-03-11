package com.myorg.workflow.ondemand;

import com.myorg.config.AnalysisServerReadinessConfig;
import com.myorg.config.OnDemandWorkflowConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.constructs.Construct;

import java.util.List;

/**
 * analysis batch 워크플로우 보조 Lambda 생성 팩토리.
 */
public final class OnDemandSupportFunctionFactory {
    private OnDemandSupportFunctionFactory() {
    }

    public static Function createAnalysisServerProbe(
            Construct scope,
            String id,
            AnalysisServerReadinessConfig config,
            IVpc vpc,
            List<ISubnet> subnets,
            List<ISecurityGroup> securityGroups
    ) {
        int timeoutSeconds = Math.max(config.probeTimeoutSeconds() + 5, 10);

        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.probeLambdaAssetPath()))
                .timeout(Duration.seconds(timeoutSeconds))
                .memorySize(config.probeLambdaMemoryMb())
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnets(subnets).build())
                .securityGroups(securityGroups)
                .build());
    }

    public static Function createBusinessValidator(
            Construct scope,
            String id,
            OnDemandWorkflowConfig config
    ) {
        // Business validator는 입력 payload 기반 검증만 수행한다.
        return new Function(scope, id, FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.lambdaConfig().businessValidatorLambdaAssetPath()))
                .timeout(Duration.seconds(30))
                .memorySize(config.lambdaConfig().businessValidatorMemoryMb())
                .build());
    }
}
