package com.myorg.config.dynamodb;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

public record DynamoDBConfig(
        String lockTableName,
        String lockTableArn,
        String lockKey
) {
    public static DynamoDBConfig fromEnv() {
        return new DynamoDBConfig(
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_LOCK_TABLE_NAME),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_LOCK_TABLE_ARN),
                AppConfig.getValueOrDefault(EnvKey.ON_DEMAND_LOCK_KEY)
        );
    }
}
