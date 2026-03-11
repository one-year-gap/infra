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
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.msk.CfnClusterProps;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Recommendation realtime consumer 연결용 Provisioned MSK 스택.
 */
public class MskStack extends Stack {
    private final CfnCluster cluster;
    private final String bootstrapBrokersSaslIam;

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

        this.cluster = new CfnCluster(this, "ProvisionedCluster",
                CfnClusterProps.builder()
                        .clusterName(AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME))
                        .kafkaVersion(AppConfig.getValueOrDefault(EnvKey.MSK_KAFKA_VERSION))
                        .numberOfBrokerNodes(Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MSK_BROKER_NODES)))
                        .brokerNodeGroupInfo(CfnCluster.BrokerNodeGroupInfoProperty.builder()
                                .clientSubnets(privateSubnetIds)
                                .instanceType(AppConfig.getValueOrDefault(EnvKey.MSK_BROKER_INSTANCE_TYPE))
                                .securityGroups(List.of(kafkaBrokerSg.getSecurityGroupId()))
                                .storageInfo(CfnCluster.StorageInfoProperty.builder()
                                        .ebsStorageInfo(CfnCluster.EBSStorageInfoProperty.builder()
                                                .volumeSize(Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MSK_BROKER_VOLUME_GIB)))
                                                .build())
                                        .build())
                                .build())
                        .clientAuthentication(CfnCluster.ClientAuthenticationProperty.builder()
                                .sasl(CfnCluster.SaslProperty.builder()
                                        .iam(CfnCluster.IamProperty.builder()
                                                .enabled(true)
                                                .build())
                                        .build())
                                .build())
                        .encryptionInfo(CfnCluster.EncryptionInfoProperty.builder()
                                .encryptionInTransit(CfnCluster.EncryptionInTransitProperty.builder()
                                        .clientBroker("TLS")
                                        .inCluster(true)
                                        .build())
                                .build())
                        .tags(Map.of(
                                "service", "recommendation-realtime",
                                "component", "kafka",
                                "managed-by", "cdk"
                        ))
                        .build());

        AwsSdkCall getBootstrapBrokers = AwsSdkCall.builder()
                .service("Kafka")
                .action("getBootstrapBrokers")
                .parameters(Map.of("ClusterArn", cluster.getAttrArn()))
                .physicalResourceId(PhysicalResourceId.of(cluster.getAttrArn()))
                .build();

        AwsCustomResource bootstrapBrokers = new AwsCustomResource(this, "BootstrapBrokersLookup",
                software.amazon.awscdk.customresources.AwsCustomResourceProps.builder()
                        .onCreate(getBootstrapBrokers)
                        .onUpdate(getBootstrapBrokers)
                        .policy(AwsCustomResourcePolicy.fromSdkCalls(
                                SdkCallsPolicyOptions.builder()
                                        .resources(List.of(cluster.getAttrArn()))
                                        .build()
                        ))
                        .build());
        bootstrapBrokers.getNode().addDependency(cluster);

        CfnOutput.Builder.create(this, "MskClusterArn")
                .value(cluster.getAttrArn())
                .description("MSK Cluster ARN")
                .build();

        CfnOutput.Builder.create(this, "MskClusterName")
                .value(AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME))
                .description("MSK Cluster Name")
                .build();

        CfnOutput.Builder.create(this, "MskBrokerSecurityGroupId")
                .value(kafkaBrokerSg.getSecurityGroupId())
                .description("Security group attached to MSK brokers")
                .build();

        this.bootstrapBrokersSaslIam = bootstrapBrokers.getResponseField("BootstrapBrokerStringSaslIam");

        CfnOutput.Builder.create(this, "MskBootstrapBrokersSaslIam")
                .value(bootstrapBrokersSaslIam)
                .description("Bootstrap brokers for IAM-authenticated clients")
                .build();
    }

    public CfnCluster getCluster() {
        return cluster;
    }

    public String getBootstrapBrokersSaslIam() {
        return bootstrapBrokersSaslIam;
    }
}
