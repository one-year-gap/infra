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
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            OnDemandWorkflowConfig config,
            IVpc vpc,
            List<ISubnet> subnets,
            List<ISecurityGroup> securityGroups
    ) {
        FunctionProps.Builder builder = FunctionProps.builder()
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset(config.lambdaConfig().businessValidatorLambdaAssetPath()))
                .timeout(Duration.seconds(30))
                .memorySize(config.lambdaConfig().businessValidatorMemoryMb())
                .environment(businessValidatorEnvironment(config));

        if (config.usesOutboxRequestCountValidation()) {
            builder.vpc(vpc);
            builder.vpcSubnets(SubnetSelection.builder().subnets(subnets).build());
            builder.securityGroups(securityGroups);
        }

        Function function = new Function(scope, id, builder.build());

        if (config.usesOutboxRequestCountValidation()) {
            importSecret(scope, id + "DbSecret", config.businessValidatorDbSecretId()).grantRead(function);
        }

        return function;
    }

    private static Map<String, String> businessValidatorEnvironment(OnDemandWorkflowConfig config) {
        Map<String, String> env = new HashMap<>();
        env.put("VALIDATION_MODE", config.businessValidationMode().name());
        env.put("DB_NAME", config.businessValidatorDbName());
        env.put("DB_SECRET_ID", config.businessValidatorDbSecretId());
        // worker task 기동과 batch metadata 기록 시점 차이를 흡수한다.
        env.put("JOB_LOOKUP_GRACE_SECONDS", "300");
        return env;
    }

    private static ISecret importSecret(Construct scope, String id, String secretId) {
        if (secretId != null && secretId.startsWith("arn:")) {
            return Secret.fromSecretCompleteArn(scope, id, secretId);
        }
        return Secret.fromSecretNameV2(scope, id, secretId);
    }
}
