package com.myorg.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

/**
 * 온디맨드 워크플로우 동시 실행 제어용 DynamoDB 락 테이블
 * - 테이블 교체/정책 변경을 워크플로우 배포와 독립적으로 진행
 */
public class OnDemandLockStack extends Stack {
    private final Table lockTable;

    public OnDemandLockStack(
            Construct scope,
            String id,
            StackProps props
    ) {
        super(scope, id, props);

        this.lockTable = Table.Builder.create(this, "OnDemandWorkflowLockTable")
                .partitionKey(Attribute.builder()
                        .name("lockKey")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        CfnOutput.Builder.create(this, "OnDemandWorkflowLockTableName")
                .value(lockTable.getTableName())
                .description("DynamoDB lock table name for on-demand workflow")
                .build();

        CfnOutput.Builder.create(this, "OnDemandWorkflowLockTableArn")
                .value(lockTable.getTableArn())
                .description("DynamoDB lock table ARN for on-demand workflow")
                .build();
    }

    public Table getLockTable() {
        return lockTable;
    }
}
