package com.myorg.stacks;

import com.myorg.config.RepositoryConfig;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.*;
import software.constructs.Construct;

import java.util.List;

public class EcrStack extends Stack {
    private final Repository apiServerRepo;
    private final Repository adminWebRepo;

    public EcrStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.apiServerRepo = new Repository(
                this,
                "API-SERVER-REPO",
                RepositoryProps.builder()
                        .repositoryName(RepositoryConfig.getApiServerRepository())
                        .imageScanOnPush(true)
                        .imageTagMutability(TagMutability.IMMUTABLE)
                        .removalPolicy(RemovalPolicy.RETAIN)
                        .lifecycleRules(
                                List.of(
                                        LifecycleRule
                                                .builder()
                                                .tagStatus(TagStatus.UNTAGGED)
                                                .description("expire untagged images after 7 days")
                                                .maxImageAge(Duration.days(7))
                                                .build(),
                                        LifecycleRule
                                                .builder()
                                                .tagStatus(TagStatus.ANY)
                                                .description("maintain latest images 10")
                                                .maxImageCount(10)
                                                .build())
                        )
                        .build()
        );

        this.adminWebRepo = new Repository(
                this,
                "ADMIN-WEB-REPO",
                RepositoryProps.builder()
                        .repositoryName(RepositoryConfig.getAdminWebRepository())
                        .imageTagMutability(TagMutability.IMMUTABLE)
                        .imageScanOnPush(true)
                        .removalPolicy(RemovalPolicy.RETAIN)
                        .lifecycleRules(
                                List.of(
                                        LifecycleRule
                                                .builder()
                                                .tagStatus(TagStatus.UNTAGGED)
                                                .description("expire untagged images after 7 days")
                                                .maxImageAge(Duration.days(7))
                                                .build(),
                                        LifecycleRule
                                                .builder()
                                                .tagStatus(TagStatus.ANY)
                                                .description("maintain latest images 10")
                                                .maxImageCount(10)
                                                .build())
                        )
                        .build()
        );
    }

    public Repository getApiServerRepo() {
        return apiServerRepo;
    }

    public Repository getAdminWebRepo() {
        return adminWebRepo;
    }

}
