package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kafkaconnect.CfnConnector;
import software.amazon.awscdk.services.kafkaconnect.CfnCustomPlugin;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.assets.Asset;
import software.constructs.Construct;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 클릭 로그 S3 sink용 MSK Connect 스택.
 */
public class MskConnectStack extends Stack {
    private static final String CONNECTOR_NAME = "click-log-s3-sink";
    private static final String RAW_PREFIX = "events/raw";
    private static final String HOURLY_PATH_FORMAT = "'dt'=YYYY-MM-dd/'hour'=HH";
    private static final String HOURLY_PARTITION_DURATION_MS = "3600000";
    private static final String MSK_CONNECT_INTERNAL_PREFIX = "__amazon_msk_connect_";
    private static final String MSK_CONNECT_RUNTIME_GROUP_PREFIX = "connect-";

    /**
     * MSK Connect 커넥터 구성.
     */
    public MskConnectStack(
            Construct scope,
            String id,
            StackProps props,
            Vpc vpc,
            SecurityGroup kafkaBrokerSg,
            SecurityGroup kafkaConnectSg,
            String mskClusterName,
            String mskBootstrapBrokers,
            String mskClusterArn,
            String clickLogTopicName,
            Bucket clickLogBucket
    ) {
        super(scope, id, props);

        // private subnet 선택
        var privateSubnets = vpc.selectSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build());
        List<String> privateSubnetIds = privateSubnets.getSubnetIds();

        // 플러그인 asset 경로
        Asset s3SinkPluginAsset = Asset.Builder.create(this, "S3SinkPluginAsset")
                .path(resolvePluginAssetPath())
                .build();

        // custom plugin 리소스
        CfnCustomPlugin s3SinkPlugin = CfnCustomPlugin.Builder.create(this, "ClickLogS3SinkPlugin")
                .name("click-log-s3-sink-plugin")
                .contentType("ZIP")
                .location(CfnCustomPlugin.CustomPluginLocationProperty.builder()
                        .s3Location(CfnCustomPlugin.S3LocationProperty.builder()
                                .bucketArn("arn:aws:s3:::" + s3SinkPluginAsset.getS3BucketName())
                                .fileKey(s3SinkPluginAsset.getS3ObjectKey())
                                .build())
                        .build())
                .build();

        // 커넥터 실행 역할
        Role connectorRole = Role.Builder.create(this, "ClickLogConnectorRole")
                .assumedBy(new ServicePrincipal("kafkaconnect.amazonaws.com"))
                .build();

        // S3 적재 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "s3:PutObject",
                        "s3:AbortMultipartUpload",
                        "s3:ListBucket",
                        "s3:GetBucketLocation"
                ))
                .resources(List.of(
                        clickLogBucket.getBucketArn(),
                        clickLogBucket.getBucketArn() + "/*"
                ))
                .build());

        // MSK cluster 접속 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "kafka-cluster:Connect",
                        "kafka-cluster:DescribeCluster"
                ))
                .resources(List.of(mskClusterArn))
                .build());

        // source topic 읽기 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "kafka-cluster:DescribeTopic",
                        "kafka-cluster:ReadData"
                ))
                .resources(List.of(buildTopicArn(mskClusterName, clickLogTopicName)))
                .build());

        // internal topic 생성 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "kafka-cluster:CreateTopic",
                        "kafka-cluster:DescribeTopic",
                        "kafka-cluster:ReadData",
                        "kafka-cluster:WriteData"
                ))
                .resources(List.of(buildTopicArnPattern(mskClusterName, MSK_CONNECT_INTERNAL_PREFIX + "*")))
                .build());

        // internal group 제어 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "kafka-cluster:DescribeGroup",
                        "kafka-cluster:AlterGroup"
                ))
                .resources(List.of(buildGroupArnPattern(mskClusterName, MSK_CONNECT_INTERNAL_PREFIX + "*")))
                .build());

        // runtime consumer group 권한
        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "kafka-cluster:DescribeGroup",
                        "kafka-cluster:AlterGroup"
                ))
                .resources(List.of(buildGroupArnPattern(mskClusterName, MSK_CONNECT_RUNTIME_GROUP_PREFIX + "*")))
                .build());

        // CloudWatch 로그 그룹 참조
        ILogGroup connectorLogGroup = LogGroup.fromLogGroupName(
                this,
                "ClickLogConnectorLogGroup",
                "/holliverse/msk-connect/click-log-s3-sink"
        );

        connectorRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "logs:CreateLogStream",
                        "logs:PutLogEvents",
                        "logs:DescribeLogStreams"
                ))
                .resources(List.of(
                        connectorLogGroup.getLogGroupArn(),
                        connectorLogGroup.getLogGroupArn() + ":*"
                ))
                .build());

        // S3 sink 커넥터 리소스
        CfnConnector connector = CfnConnector.Builder.create(this, "ClickLogS3SinkConnector")
                .connectorName(CONNECTOR_NAME)
                .kafkaConnectVersion(AppConfig.getValueOrDefault(EnvKey.MSK_CONNECT_VERSION))
                .capacity(CfnConnector.CapacityProperty.builder()
                        .provisionedCapacity(CfnConnector.ProvisionedCapacityProperty.builder()
                                .mcuCount(Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MSK_CONNECT_MCU_COUNT)))
                                .workerCount(Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MSK_CONNECT_WORKER_COUNT)))
                                .build())
                        .build())
                .connectorConfiguration(Map.ofEntries(
                        Map.entry("connector.class", "io.confluent.connect.s3.S3SinkConnector"),
                        Map.entry("tasks.max", "1"),
                        Map.entry("topics", clickLogTopicName),
                        // raw 로그 prefix
                        Map.entry("topics.dir", RAW_PREFIX),
                        Map.entry("s3.bucket.name", clickLogBucket.getBucketName()),
                        Map.entry("s3.region", getRegion()),
                        Map.entry("storage.class", "io.confluent.connect.s3.storage.S3Storage"),
                        Map.entry("format.class", "io.confluent.connect.s3.format.json.JsonFormat"),
                        Map.entry("flush.size", "1000"),
                        Map.entry("rotate.interval.ms", "60000"),
                        // 시간 기반 파티셔너
                        Map.entry("partitioner.class", "io.confluent.connect.storage.partitioner.TimeBasedPartitioner"),
                        // day+hour 경로
                        Map.entry("path.format", HOURLY_PATH_FORMAT),
                        // hourly 파티션 길이
                        Map.entry("partition.duration.ms", HOURLY_PARTITION_DURATION_MS),
                        // ingest_time 기준
                        Map.entry("timestamp.extractor", "Wallclock"),
                        Map.entry("timezone", "Asia/Seoul"),
                        Map.entry("locale", "ko_KR"),
                        Map.entry("key.converter", "org.apache.kafka.connect.storage.StringConverter"),
                        Map.entry("value.converter", "org.apache.kafka.connect.json.JsonConverter"),
                        Map.entry("value.converter.schemas.enable", "false")
                ))
                .kafkaCluster(CfnConnector.KafkaClusterProperty.builder()
                        .apacheKafkaCluster(CfnConnector.ApacheKafkaClusterProperty.builder()
                                .bootstrapServers(mskBootstrapBrokers)
                                .vpc(CfnConnector.VpcProperty.builder()
                                        .subnets(privateSubnetIds)
                                        .securityGroups(List.of(kafkaConnectSg.getSecurityGroupId()))
                                        .build())
                                .build())
                        .build())
                .kafkaClusterClientAuthentication(CfnConnector.KafkaClusterClientAuthenticationProperty.builder()
                        .authenticationType("IAM")
                        .build())
                .kafkaClusterEncryptionInTransit(CfnConnector.KafkaClusterEncryptionInTransitProperty.builder()
                        .encryptionType("TLS")
                        .build())
                .plugins(List.of(
                        CfnConnector.PluginProperty.builder()
                                .customPlugin(CfnConnector.CustomPluginProperty.builder()
                                        .customPluginArn(s3SinkPlugin.getAttrCustomPluginArn())
                                        .revision(1)
                                        .build())
                                .build()
                ))
                .serviceExecutionRoleArn(connectorRole.getRoleArn())
                .logDelivery(CfnConnector.LogDeliveryProperty.builder()
                        .workerLogDelivery(CfnConnector.WorkerLogDeliveryProperty.builder()
                                .cloudWatchLogs(CfnConnector.CloudWatchLogsLogDeliveryProperty.builder()
                                        .enabled(true)
                                        .logGroup(connectorLogGroup.getLogGroupName())
                                        .build())
                                .build())
                        .build())
                .build();

        // 커넥터 이름 출력
        CfnOutput.Builder.create(this, "ClickLogConnectorName")
                .value(CONNECTOR_NAME)
                .description("Click log S3 sink connector name")
                .build();

        // 커넥터 ARN 출력
        CfnOutput.Builder.create(this, "ClickLogConnectorArn")
                .value(connector.getAttrConnectorArn())
                .description("Click log S3 sink connector arn")
                .build();
    }

    /**
     * plugin asset 경로 해석.
     */
    private String resolvePluginAssetPath() {
        String configuredPath = AppConfig.getValueOrDefault(EnvKey.MSK_CONNECT_PLUGIN_ASSET_PATH);
        Path directPath = Path.of(configuredPath);
        if (Files.exists(directPath)) {
            return directPath.toString();
        }

        Path repositoryPath = Path.of("infrastructure", configuredPath);
        if (Files.exists(repositoryPath)) {
            return repositoryPath.toString();
        }

        return directPath.toString();
    }

    /**
     * topic ARN 패턴 계산.
     */
    private String buildTopicArn(String mskClusterName, String topicName) {
        return String.format(
                "arn:aws:kafka:%s:%s:topic/%s/*/%s",
                getRegion(),
                getAccount(),
                mskClusterName,
                topicName
        );
    }

    /**
     * topic ARN 패턴 계산.
     */
    private String buildTopicArnPattern(String mskClusterName, String topicNamePattern) {
        return String.format(
                "arn:aws:kafka:%s:%s:topic/%s/*/%s",
                getRegion(),
                getAccount(),
                mskClusterName,
                topicNamePattern
        );
    }

    /**
     * group ARN 패턴 계산.
     */
    private String buildGroupArnPattern(String mskClusterName, String groupNamePattern) {
        return String.format(
                "arn:aws:kafka:%s:%s:group/%s/*/%s",
                getRegion(),
                getAccount(),
                mskClusterName,
                groupNamePattern
        );
    }
}
