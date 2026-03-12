package com.myorg.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;

/**
 * 클릭 로그 원본 보관용 S3 스택.
 */
public class ClickLogBucketStack extends Stack {
    private final Bucket bucket;

    /**
     * raw click log 버킷 구성.
     */
    public ClickLogBucketStack(
            Construct scope,
            String id,
            StackProps props,
            String bucketName
    ) {
        super(scope, id, props);

        // raw click log 버킷
        this.bucket = Bucket.Builder.create(this, "ClickLogRawBucket")
                .bucketName(bucketName)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .versioned(false)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // 버킷 이름 출력
        CfnOutput.Builder.create(this, "ClickLogRawBucketName")
                .value(bucket.getBucketName())
                .description("Raw click log S3 bucket name")
                .build();
    }

    public Bucket getBucket() {
        return bucket;
    }
}
