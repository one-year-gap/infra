package com.myorg.config;

public record WatchDogConfig(
        String watchdogLambdaAssetPath,
        int watchdogMemoryMb,
        boolean enableWatchdog,
        String watchdogScheduleExpression,
        int watchdogIdleMinutes
) {

    public static WatchDogConfig fromEnv() {
        return new WatchDogConfig(
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WATCHDOG_LAMBDA_ASSET_PATH),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WATCHDOG_MEMORY_MB)),
                Boolean.getBoolean(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_ENABLE_WATCHDOG)),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WATCHDOG_SCHEDULE_EXPRESSION),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_WATCHDOG_IDLE_MINUTES))
        );
    }
}
