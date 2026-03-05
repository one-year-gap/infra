package com.myorg.config;

import software.amazon.awscdk.App;

public record LambdaConfig(
        String readyProbeLambdaAssetPath,
        int readyProbeMemoryMb,

        String runtimeGuardLambdaAssetPath,
        int runtimeGuardMemoryMb,

        String businessValidatorLambdaAssetPath,
        int businessValidatorMemoryMb
) {
    public static LambdaConfig fromEnv() {
        return new LambdaConfig(
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_PROBE_LAMBDA_ASSET_PATH),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_READY_PROBE_MEMORY_MB)),

                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_RUNTIME_GUARD_LAMBDA_ASSET_PATH),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_RUNTIME_GUARD_MEMORY_MB)),

                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_LAMBDA_ASSET_PATH),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_BUSINESS_VALIDATOR_MEMORY_MB)));
    }
}