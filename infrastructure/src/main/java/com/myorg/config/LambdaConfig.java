package com.myorg.config;

import software.amazon.awscdk.App;

public record LambdaConfig(
        String businessValidatorLambdaAssetPath,
        int businessValidatorMemoryMb
) {
    public static LambdaConfig fromEnv() {
        return new LambdaConfig(
                AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_LAMBDA_ASSET_PATH.key(), "lambda/business-validator"),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_MEMORY_MB.key(), "256")));
    }
}
