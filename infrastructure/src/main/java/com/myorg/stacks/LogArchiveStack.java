package com.myorg.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.s3.StorageClass;
import software.amazon.awscdk.services.s3.Transition;
import software.constructs.Construct;

import java.util.List;

/**
 * 시스템 로그 장기 보관용 S3 Stack.
 * - 버킷 생성
 * - 수명주기(Lifecycle) 티어링 정책 적용
 */
public class LogArchiveStack extends Stack {
    private final Bucket bucket;

    public LogArchiveStack(
            Construct scope,
            String id,
            StackProps props,
            String bucketName
    ) {
        super(scope, id, props);

        this.bucket = Bucket.Builder.create(this, "LokiArchiveBucket")
                .bucketName(bucketName)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .removalPolicy(RemovalPolicy.RETAIN)
                // Lifecycle 정책: 전용 Loki 로그 버킷 전체 객체에 티어링/만료 적용.
                .lifecycleRules(List.of(
                        LifecycleRule.builder()
                                .id("loki-tiering-v1")
                                .enabled(true)

                                // 미완료 멀티파트 업로드 정리.
                                .abortIncompleteMultipartUploadAfter(Duration.days(7))

                                // 30일: STANDARD_IA
                                // 90일: GLACIER_IR
                                // 180일: DEEP_ARCHIVE
                                .transitions(List.of(
                                        //STANDARD_IA
                                        Transition.builder()
                                                .transitionAfter(Duration.days(30))
                                                .storageClass(StorageClass.INFREQUENT_ACCESS)
                                                .build(),
                                        //GLACIER_IR
                                        Transition.builder()
                                                .transitionAfter(Duration.days(90))
                                                .storageClass(StorageClass.GLACIER_INSTANT_RETRIEVAL)
                                                .build(),
                                        //DEEP_ARCHIVE
                                        Transition.builder()
                                                .transitionAfter(Duration.days(180))
                                                .storageClass(StorageClass.DEEP_ARCHIVE)
                                                .build()
                                ))
                                // 365일 경과: 만료 삭제.
                                .expiration(Duration.days(365))
                                .noncurrentVersionExpiration(Duration.days(1))
                                .expiredObjectDeleteMarker(true)
                                .build()
                ))
                .build();

        CfnOutput.Builder.create(this, "LokiArchiveBucketName")
                .value(bucket.getBucketName())
                .description("Loki log archive S3 bucket name")
                .build();
    }

    public Bucket getBucket() {
        return bucket;
    }
}
