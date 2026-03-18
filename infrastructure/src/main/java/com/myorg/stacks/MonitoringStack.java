package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.constants.MonitoringConstants;
import com.myorg.props.MonitoringStackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.BlockDevice;
import software.amazon.awscdk.services.ec2.BlockDeviceVolume;
import software.amazon.awscdk.services.ec2.EbsDeviceOptions;
import software.amazon.awscdk.services.ec2.EbsDeviceVolumeType;
import software.amazon.awscdk.services.ec2.Instance;
import software.amazon.awscdk.services.ec2.MachineImage;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.UserData;
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
    private static final String REMOTE_HOST_PORT_FORWARD_DOCUMENT = "AWS-StartPortForwardingSessionToRemoteHost";
    private static final int LOCAL_PORT_FORWARD_OFFSET = 10_000;
    private static final String MONITORING_CLOUD_MAP_INSTANCE_ID = "monitoring-ec2";

    public MonitoringStack(
            Construct scope,
            String id,
            StackProps props,
            MonitoringStackProps stackProps) {
        super(scope, id, props);

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
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "route53:ListHostedZonesByName",
                        "servicediscovery:ListServices",
                        "servicediscovery:RegisterInstance"))
                .resources(List.of("*"))
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
        userData.addCommands(buildCloudMapRegistrationCommands(
                AppConfig.getInternalDomainName(),
                stackProps.config().grafanaConfig().grafanaServiceName()
        ).toArray(String[]::new));

        this.grafanaInstance = Instance.Builder.create(this, "GrafanaServer")
                .vpc(stackProps.vpc())
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(stackProps.config().toSubnetType())
                        .build())
                .instanceType(stackProps.config().toInstanceType())
                .machineImage(MachineImage.latestAmazonLinux2023())
                .securityGroup(stackProps.monitoringSg())
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

    private List<String> buildCloudMapRegistrationCommands(String internalDomainName, String serviceName) {
        String privateZoneName = internalDomainName.endsWith(".")
                ? internalDomainName
                : internalDomainName + ".";

        return List.of(
                "cat <<'EOF' >/opt/monitoring/register-grafana-cloudmap.sh",
                "#!/bin/bash",
                "set -euo pipefail",
                "DOMAIN_NAME=\"" + internalDomainName + "\"",
                "PRIVATE_ZONE_NAME=\"" + privateZoneName + "\"",
                "SERVICE_NAME=\"" + serviceName + "\"",
                "INSTANCE_ID=\"" + MONITORING_CLOUD_MAP_INSTANCE_ID + "\"",
                "TOKEN=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 21600')",
                "PRIVATE_IP=$(curl -fsS -H \"X-aws-ec2-metadata-token: $TOKEN\" http://169.254.169.254/latest/meta-data/local-ipv4)",
                "NAMESPACE_ARN=$(aws route53 list-hosted-zones-by-name --dns-name \"$DOMAIN_NAME\" --query \"HostedZones[?Name=='$PRIVATE_ZONE_NAME'].LinkedService.Description | [0]\" --output text)",
                "if [ -z \"$NAMESPACE_ARN\" ] || [ \"$NAMESPACE_ARN\" = \"None\" ]; then",
                "  echo \"Cloud Map namespace not found for $DOMAIN_NAME\"",
                "  exit 0",
                "fi",
                "NAMESPACE_ID=\"${NAMESPACE_ARN##*/}\"",
                "SERVICE_ID=$(aws servicediscovery list-services --filters Name=NAMESPACE_ID,Values=\"$NAMESPACE_ID\",Condition=EQ --query \"Services[?Name=='$SERVICE_NAME'].Id | [0]\" --output text)",
                "if [ -z \"$SERVICE_ID\" ] || [ \"$SERVICE_ID\" = \"None\" ]; then",
                "  echo \"Cloud Map service not found for $SERVICE_NAME\"",
                "  exit 0",
                "fi",
                "aws servicediscovery register-instance --service-id \"$SERVICE_ID\" --instance-id \"$INSTANCE_ID\" --attributes AWS_INSTANCE_IPV4=\"$PRIVATE_IP\"",
                "EOF",
                "chmod +x /opt/monitoring/register-grafana-cloudmap.sh",
                "/opt/monitoring/register-grafana-cloudmap.sh || echo 'Cloud Map registration skipped'"
        );
    }
}
