package com.myorg.stacks;

import com.myorg.config.RepositoryConfig;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.constructs.Construct;

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
