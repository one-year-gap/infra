package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.constants.MonitoringConstants;
import com.myorg.constants.NetworkConstants;
import com.myorg.props.MonitoringStackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.s3.assets.Asset;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.List;

/**
 * Grafana Monitoring stack
 */
public class MonitoringStack extends Stack {
    private final Instance grafanaInstance;
    private static final int[] PINPOINT_COLLECTOR_PORTS = {9991, 9992, 9993};
    private static final int MSK_IAM_PORT = 9098;
    private static final String REMOTE_HOST_PORT_FORWARD_DOCUMENT = "AWS-StartPortForwardingSessionToRemoteHost";
    private static final int LOCAL_PORT_FORWARD_OFFSET = 10_000;

    public MonitoringStack(
            Construct scope,
            String id,
            StackProps props,
            MonitoringStackProps stackProps) {
        super(scope, id, props);

        SecurityGroup grafanaSg = SecurityGroup.Builder.create(this, "MonitoringSg")
                .vpc(stackProps.vpc())
                .description("Grafana Security Group")
                .allowAllOutbound(true)
                .build();

        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToDbIngress")
                .groupId(stackProps.dbSg().getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(NetworkConstants.PORT_POSTGRES)
                .toPort(NetworkConstants.PORT_POSTGRES)
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to DB PostgreSQL")
                .build();
        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToAdminApiIngress")
                .groupId(stackProps.adminApiSg().getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(stackProps.adminApiPort())
                .toPort(stackProps.adminApiPort())
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to Admin API Actuator")
                .build();
        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToCustomerApiIngress")
                .groupId(stackProps.customerApiSg().getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(stackProps.customerApiPort())
                .toPort(stackProps.customerApiPort())
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to Customer API Actuator")
                .build();
        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToMskIngress")
                .groupId(stackProps.kafkaBrokerSg().getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(MSK_IAM_PORT)
                .toPort(MSK_IAM_PORT)
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to MSK IAM")
                .build();

        for (int port : PINPOINT_COLLECTOR_PORTS) {
            CfnSecurityGroupIngress.Builder.create(this, "PinpointFromAdminApiIngress" + port)
                    .groupId(grafanaSg.getSecurityGroupId())
                    .ipProtocol("tcp")
                    .fromPort(port)
                    .toPort(port)
                    .sourceSecurityGroupId(stackProps.adminApiSg().getSecurityGroupId())
                    .description("Admin API to Pinpoint collector " + port)
                    .build();
            CfnSecurityGroupIngress.Builder.create(this, "PinpointFromCustomerApiIngress" + port)
                    .groupId(grafanaSg.getSecurityGroupId())
                    .ipProtocol("tcp")
                    .fromPort(port)
                    .toPort(port)
                    .sourceSecurityGroupId(stackProps.customerApiSg().getSecurityGroupId())
                    .description("Customer API to Pinpoint collector " + port)
                    .build();

            CfnSecurityGroupEgress.Builder.create(this, "AdminApiToPinpointEgress" + port)
                    .groupId(stackProps.adminApiSg().getSecurityGroupId())
                    .ipProtocol("tcp")
                    .fromPort(port)
                    .toPort(port)
                    .destinationSecurityGroupId(grafanaSg.getSecurityGroupId())
                    .description("Admin API egress to Pinpoint collector " + port)
                    .build();
            CfnSecurityGroupEgress.Builder.create(this, "CustomerApiToPinpointEgress" + port)
                    .groupId(stackProps.customerApiSg().getSecurityGroupId())
                    .ipProtocol("tcp")
                    .fromPort(port)
                    .toPort(port)
                    .destinationSecurityGroupId(grafanaSg.getSecurityGroupId())
                    .description("Customer API egress to Pinpoint collector " + port)
                    .build();
        }

        Role grafanaRole = Role.Builder.create(this, "GrafanaEc2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        MonitoringConstants.POLICY_INSTANCE_CORE,
                        MonitoringConstants.POLICY_CLOUD_WATCH,
                        MonitoringConstants.POLICY_AWS_XRAY))
                .build();
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "secretsmanager:GetSecretValue",
                        "secretsmanager:DescribeSecret"))
                .resources(List.of(stackProps.config().dbSecretArnPattern(this.getRegion(),
                        this.getAccount())))
                .build());

        /*
         * =================================================================
         *                     Grafana Log Access Role
         * =================================================================
         */
        //CloudWatch 로그 그룹 메타 조회
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("logs:DescribeLogGroups", "logs:DescribeLogStreams"))
                .resources(List.of("*"))
                .build());

        // 실제 로그 읽기 권한: 대상 로그 그룹으로 제한
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("logs:GetLogEvents", "logs:FilterLogEvents"))
                .resources(stackProps.config().cloudWatchLogGroupArns(this.getRegion(), this.getAccount()))
                .build());

        // Loki S3 저장소 접근
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("s3:ListBucket"))
                .resources(List.of(stackProps.config().lokiS3BucketArn()))
                .build());
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject"))
                .resources(List.of(stackProps.config().lokiS3ObjectArn()))
                .build());

        // Kafka UI runs on this host and authenticates to MSK with the instance role.
        String clusterName = AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME);
        String clusterArnPattern = String.format(
                "arn:aws:kafka:%s:%s:cluster/%s/*",
                this.getRegion(),
                this.getAccount(),
                clusterName
        );
        String topicArnPattern = String.format(
                "arn:aws:kafka:%s:%s:topic/%s/*",
                this.getRegion(),
                this.getAccount(),
                clusterName
        );
        String groupArnPattern = String.format(
                "arn:aws:kafka:%s:%s:group/%s/*",
                this.getRegion(),
                this.getAccount(),
                clusterName
        );

        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "kafka-cluster:Connect",
                        "kafka-cluster:DescribeCluster"
                ))
                .resources(List.of(clusterArnPattern))
                .build());
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "kafka-cluster:CreateTopic",
                        "kafka-cluster:DeleteTopic",
                        "kafka-cluster:AlterTopic",
                        "kafka-cluster:DescribeTopic",
                        "kafka-cluster:DescribeTopicDynamicConfiguration",
                        "kafka-cluster:AlterTopicDynamicConfiguration",
                        "kafka-cluster:ReadData",
                        "kafka-cluster:WriteData"
                ))
                .resources(List.of(topicArnPattern))
                .build());
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "kafka-cluster:DescribeGroup",
                        "kafka-cluster:AlterGroup"
                ))
                .resources(List.of(groupArnPattern))
                .build());

        Path monitoringBootstrapAssetPath = stackProps.config().renderMonitoringBootstrapAsset(
                AppConfig.getRegion(),
                AppConfig.getInternalDomainName(),
                stackProps.adminApiPort(),
                stackProps.customerApiPort(),
                stackProps.mskBootstrapBrokersSaslIam()
        );
        Asset monitoringBootstrapAsset = Asset.Builder.create(this, "MonitoringBootstrapAsset")
                .path(monitoringBootstrapAssetPath.toString())
                .build();
        monitoringBootstrapAsset.grantRead(grafanaRole);

        UserData userData = UserData.forLinux();
        userData.addCommands(
                "set -euxo pipefail",
                "dnf install -y unzip",
                "aws s3 cp s3://" + monitoringBootstrapAsset.getS3BucketName() + "/"
                        + monitoringBootstrapAsset.getS3ObjectKey() + " /tmp/monitoring-bootstrap.zip",
                "unzip -o /tmp/monitoring-bootstrap.zip -d /",
                "chmod +x /opt/monitoring/install-monitoring.sh",
                "/opt/monitoring/install-monitoring.sh"
        );

        this.grafanaInstance = Instance.Builder.create(this, "GrafanaServer")
                .vpc(stackProps.vpc())
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(stackProps.config().toSubnetType())
                        .build())
                .instanceType(stackProps.config().toInstanceType())
                .machineImage(MachineImage.latestAmazonLinux2023())
                .securityGroup(grafanaSg)
                .role(grafanaRole)
                .userData(userData)
                .blockDevices(List.of(
                        BlockDevice.builder()
                                .deviceName("/dev/xvda")
                                .volume(BlockDeviceVolume.ebs(
                                        stackProps.config().rootVolumeGib(),
                                        EbsDeviceOptions.builder()
                                                .volumeType(EbsDeviceVolumeType.GP3)
                                                .encrypted(true)
                                                .build()))
                                .build()))
                .build();

        CfnOutput.Builder.create(this, "GrafanaInstanceId")
                .value(grafanaInstance.getInstanceId())
                .description("SSM target instance Id")
                .build();

        CfnOutput.Builder.create(this, "GrafanaPortForward")
                .value("aws ssm start-session --target " + grafanaInstance.getInstanceId()
                       + " --document-name " + stackProps.config().ssmPortForwardDocument()
                       + " --parameters '"
                       + stackProps.config().grafanaConfig().ssmPortForwardParametersJson()
                       + "'")
                .description("Port forward command for local Grafana access")
                .build();
        CfnOutput.Builder.create(this, "PinpointPortForward")
                .value("aws ssm start-session --target " + grafanaInstance.getInstanceId()
                       + " --document-name " + stackProps.config().ssmPortForwardDocument()
                       + " --parameters '"
                       + stackProps.config().pinpointConfig().ssmPortForwardParametersJson(
                        stackProps.config().ssmPortForwardDocument())
                       + "'")
                .description("Port forward command for local Pinpoint access")
                .build();
        CfnOutput.Builder.create(this, "KafkaUiPortForward")
                .value("aws ssm start-session --target " + grafanaInstance.getInstanceId()
                       + " --document-name " + stackProps.config().ssmPortForwardDocument()
                       + " --parameters '"
                       + stackProps.config().kafkaUiConfig().ssmPortForwardParametersJson()
                       + "'")
                .description("Port forward command for local Kafka UI access")
                .build();

        String adminApiHost = stackProps.config().adminApiServiceDnsLabel() + "." + AppConfig.getInternalDomainName();
        CfnOutput.Builder.create(this, "AdminApiPortForward")
                .value(buildRemoteHostPortForwardCommand(
                        grafanaInstance.getInstanceId(),
                        adminApiHost,
                        stackProps.adminApiPort(),
                        stackProps.adminApiPort() + LOCAL_PORT_FORWARD_OFFSET
                ))
                .description("Port forward command for local Admin API access")
                .build();
    }

    public Instance getGrafanaInstance() {
        return grafanaInstance;
    }

    private String buildRemoteHostPortForwardCommand(
            String instanceId,
            String host,
            int remotePort,
            int localPort
    ) {
        return "aws ssm start-session --target " + instanceId
               + " --document-name " + REMOTE_HOST_PORT_FORWARD_DOCUMENT
               + " --parameters '{\"host\":[\"" + host + "\"],\"portNumber\":[\"" + remotePort
               + "\"],\"localPortNumber\":[\"" + localPort + "\"]}'";
    }
}
