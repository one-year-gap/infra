package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

import java.util.Map;

class ClickLogBucketStackTest {

    @Test
    @DisplayName("Click log bucket 스택 기본 리소스가 생성되어야 한다.")
    void should_create_click_log_bucket_resources() {
        App app = new App();

        ClickLogBucketStack stack = new ClickLogBucketStack(
                app,
                "ClickLogBucketStackTest",
                StackProps.builder().build(),
                "test-click-log-bucket"
        );

        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::S3::Bucket", 1);
        template.hasResourceProperties("AWS::S3::Bucket", Map.of(
                "BucketName", "test-click-log-bucket",
                "BucketEncryption", Map.of(
                        "ServerSideEncryptionConfiguration", java.util.List.of(
                                Map.of(
                                        "ServerSideEncryptionByDefault", Map.of(
                                                "SSEAlgorithm", "AES256"
                                        )
                                )
                        )
                )
        ));
        template.hasOutput("ClickLogRawBucketName", Map.of());
    }
}
