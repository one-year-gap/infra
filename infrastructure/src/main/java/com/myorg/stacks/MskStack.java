package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.AwsCustomResource;
import software.amazon.awscdk.customresources.AwsCustomResourcePolicy;
import software.amazon.awscdk.customresources.AwsSdkCall;
import software.amazon.awscdk.customresources.PhysicalResourceId;
import software.amazon.awscdk.customresources.SdkCallsPolicyOptions;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.msk.CfnServerlessCluster;
import software.amazon.awscdk.services.msk.CfnServerlessClusterProps;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Recommendation realtime consumer 연결 MSK Serverless 전용 스택.
 */
public class MskStack extends Stack {
    private final CfnServerlessCluster serverlessCluster;

    public MskStack(
            Construct scope,
            String id,
            StackProps props,
            Vpc vpc,
            SecurityGroup kafkaBrokerSg
    ) {
        super(scope, id, props);

        // 브로커는 private subnet  배치 ECS 내부 통신으로만 접근
        List<String> privateSubnetIds = vpc.selectSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build()).getSubnetIds();

        this.serverlessCluster = new CfnServerlessCluster(this, "ServerlessCluster",
                CfnServerlessClusterProps.builder()
                        .clusterName(AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME))
                        .clientAuthentication(CfnServerlessCluster.ClientAuthenticationProperty.builder()
                                .sasl(CfnServerlessCluster.SaslProperty.builder()
                                        .iam(CfnServerlessCluster.IamProperty.builder()
                                                .enabled(true)
                                                .build())
                                        .build())
                                .build())
                        .vpcConfigs(List.of(
                                CfnServerlessCluster.VpcConfigProperty.builder()
                                        .subnetIds(privateSubnetIds)
                                        .securityGroups(List.of(kafkaBrokerSg.getSecurityGroupId()))
                                        .build()
                        ))
                        .tags(Map.of(
                                "service", "recommendation-realtime",
                                "component", "kafka",
                                "managed-by", "cdk"
                        ))
                        .build());

        AwsSdkCall getBootstrapBrokers = AwsSdkCall.builder()
                .service("MSK")
                .action("getBootstrapBrokers")
                .parameters(Map.of("ClusterArn", serverlessCluster.getAttrArn()))
                .physicalResourceId(PhysicalResourceId.of(serverlessCluster.getAttrArn()))
                .build();

        AwsCustomResource bootstrapBrokers = new AwsCustomResource(this, "BootstrapBrokersLookup",
                software.amazon.awscdk.customresources.AwsCustomResourceProps.builder()
                        .onCreate(getBootstrapBrokers)
                        .onUpdate(getBootstrapBrokers)
                        .policy(AwsCustomResourcePolicy.fromSdkCalls(
                                SdkCallsPolicyOptions.builder()
                                        .resources(List.of(serverlessCluster.getAttrArn()))
                                        .build()
                        ))
                        .build());
        bootstrapBrokers.getNode().addDependency(serverlessCluster);

        CfnOutput.Builder.create(this, "MskServerlessClusterArn")
                .value(serverlessCluster.getAttrArn())
                .description("MSK Serverless Cluster ARN")
                .build();

        CfnOutput.Builder.create(this, "MskServerlessClusterName")
                .value(AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME))
                .description("MSK Serverless Cluster Name")
                .build();

        CfnOutput.Builder.create(this, "MskBrokerSecurityGroupId")
                .value(kafkaBrokerSg.getSecurityGroupId())
                .description("Security group attached to MSK brokers")
                .build();

        CfnOutput.Builder.create(this, "MskBootstrapBrokersSaslIam")
                .value(bootstrapBrokers.getResponseField("BootstrapBrokerStringSaslIam"))
                .description("Bootstrap brokers for IAM-authenticated clients")
                .build();
    }

    public CfnServerlessCluster getServerlessCluster() {
        return serverlessCluster;
    }
}
