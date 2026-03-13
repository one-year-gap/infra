package com.myorg.config.monitoring;

import com.myorg.builder.ShellTemplateRenderer;
import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SubnetType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Monitoring Infra 공통 설정
 * - EC2 스펙, 네트워크, SSM, DB Secret, Prometheus/PgExporter 설정
 * - Grafana 상세 설정: {@link GrafanaConfig}
 * - Kafka UI 상세 설정: {@link KafkaUiConfig}
 * - Pinpoint 설정: {@link PinpointConfig}
 */
public record MonitoringConfig(
        // EC2 스펙
        String instanceType,
        int rootVolumeGib,
        int swapSizeGib,
        String subnetType,

        // SSM 포트포워딩 문서명
        String ssmPortForwardDocument,

        // RDS Secret
        String dbSecretNamePrefix,
        String dbSecretId,

        // Prometheus / Docker 설정
        String dockerNetworkName,
        String prometheusContainerName,
        String prometheusImage,
        int prometheusPort,
        String prometheusScrapeInterval,
        int autoDashboardPanelLimit,
        KafkaUiConfig kafkaUiConfig,

        // pg_exporter
        String pgExporterContainerName,
        String pgExporterImage,
        int pgExporterPort,
        String pgExporterExcludeDatabases,
        String pgExporterPrometheusJobName,

        // Prometheus job names
        String adminApiPrometheusJobName,
        String customerApiPrometheusJobName,

        // CloudMap service dns label
        String adminApiServiceDnsLabel,
        String customerApiServiceDnsLabel,

        //grafana, pinpoint, loki, alloy config 설정
        GrafanaConfig grafanaConfig,
        PinpointConfig pinpointConfig,
        AlloyConfig alloyConfig,
        LokiConfig lokiConfig) {


    public static MonitoringConfig fromEnv() {
        return new MonitoringConfig(
                AppConfig.getValueOrDefault(EnvKey.MONITORING_INSTANCE_TYPE),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_ROOT_VOLUME_GIB)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_SWAP_SIZE_GIB)),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_SUBNET_TYPE),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_SSM_PORT_FORWARD_DOCUMENT),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_DB_SECRET_NAME_PREFIX),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_DB_SECRET_ID),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_DOCKER_NETWORK_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PROMETHEUS_CONTAINER_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PROMETHEUS_IMAGE),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_PROMETHEUS_PORT)),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PROMETHEUS_SCRAPE_INTERVAL),
                Integer.parseInt(AppConfig
                        .getValueOrDefault(EnvKey.MONITORING_AUTO_DASHBOARD_PANEL_LIMIT)),
                KafkaUiConfig.fromEnv(),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PG_EXPORTER_CONTAINER_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PG_EXPORTER_IMAGE),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_PG_EXPORTER_PORT)),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PG_EXPORTER_EXCLUDE_DATABASES),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_PG_EXPORTER_JOB_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ADMIN_API_JOB_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_CUSTOMER_API_JOB_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_ADMIN_API_SERVICE_DNS_LABEL),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_CUSTOMER_API_SERVICE_DNS_LABEL),
                GrafanaConfig.fromEnv(),
                PinpointConfig.fromEnv(),
                AlloyConfig.fromEnv(),
                LokiConfig.fromEnv());
    }

    //ARN Prefix
    private static final String S3_PREFIX = "arn:aws:s3:::";

    public InstanceType toInstanceType() {
        String[] parts = instanceType.trim().toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException("INSTANCE_TYPE 형식이 잘못되었습니다.");
        }

        InstanceClass instanceClass = switch (parts[0]) {
            case "t3" -> InstanceClass.BURSTABLE3;
            case "t3a" -> InstanceClass.BURSTABLE3_AMD;
            case "t4g" -> InstanceClass.BURSTABLE4_GRAVITON;
            case "m6i" -> InstanceClass.STANDARD6_INTEL;
            case "m6a" -> InstanceClass.STANDARD6_AMD;
            case "m7i" -> InstanceClass.STANDARD7_INTEL;
            case "m7a" -> InstanceClass.STANDARD7_AMD;
            default -> throw new IllegalArgumentException("지원하지 않는 INSTANCE_TYPE class: " + parts[0]);
        };

        InstanceSize size = switch (parts[1]) {
            case "nano" -> InstanceSize.NANO;
            case "micro" -> InstanceSize.MICRO;
            case "small" -> InstanceSize.SMALL;
            case "medium" -> InstanceSize.MEDIUM;
            case "large" -> InstanceSize.LARGE;
            case "xlarge" -> InstanceSize.XLARGE;
            case "2xlarge" -> InstanceSize.XLARGE2;
            case "4xlarge" -> InstanceSize.XLARGE4;
            default -> throw new IllegalArgumentException("지원하지 않는 INSTANCE_TYPE size: " + parts[1]);
        };

        return InstanceType.of(instanceClass, size);
    }

    // 문자열 서브넷 타입을 CDK SubnetType으로 변환
    public SubnetType toSubnetType() {
        try {
            return SubnetType.valueOf(subnetType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("지원하지 않는 SUBNET_TYPE: " + subnetType, ex);
        }
    }

    // Secrets Manager read 정책에 사용할 ARN 패턴
    public String dbSecretArnPattern(String region, String accountId) {
        return String.format("arn:aws:secretsmanager:%s:%s:secret:%s*", region, accountId, dbSecretNamePrefix);
    }

    /**
     * Grafana 설치 UserData 커맨드
     */
    public List<String> grafanaUserDataCommands() {
        GrafanaConfig g = grafanaConfig;
        return List.of(
                "set -euxo pipefail",
                "cat <<'EOF' >/etc/yum.repos.d/" + g.grafanaRepoName() + ".repo",
                "[" + g.grafanaRepoName() + "]",
                "name=" + g.grafanaRepoName(),
                "baseurl=" + g.grafanaRepoBaseUrl(),
                "repo_gpgcheck=1",
                "enabled=1",
                "gpgcheck=1",
                "gpgkey=" + g.grafanaRepoGpgKeyUrl(),
                "sslverify=1",
                "sslcacert=/etc/pki/tls/certs/ca-bundle.crt",
                "EOF",
                "dnf install -y " + g.grafanaPackageName(),
                "sed -i -E 's|^;?allow_sign_up\\s*=\\s*true|allow_sign_up = false|' /etc/grafana/grafana.ini",
                "grep -qE '^allow_sign_up\\s*=\\s*false' /etc/grafana/grafana.ini || echo 'allow_sign_up = false' >> /etc/grafana/grafana.ini",
                "systemctl daemon-reload",
                "systemctl enable --now " + g.grafanaServiceName());
    }

    // Grafana EC2 내부 Prometheus/Exporter 컨테이너 구성 및 운영 스크립트 배포
    public List<String> monitoringBootstrapCommands(
            String region,
            String internalDomain,
            int adminApiPort,
            int customerApiPort,
            String mskBootstrapBrokersSaslIam) {
        Map<String, String> templateValues = buildTemplateValues(
                region,
                internalDomain,
                adminApiPort,
                customerApiPort,
                mskBootstrapBrokersSaslIam
        );

        List<String> commands = new ArrayList<>();
        commands.add("mkdir -p " + MonitoringPaths.BASE_DIR);

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PREPARE_HOST,
                MonitoringPaths.PREPARE_HOST_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.PREPARE_HOST_SCRIPT);
        commands.add(MonitoringPaths.PREPARE_HOST_SCRIPT);

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PROMETHEUS,
                MonitoringPaths.PROMETHEUS_CONFIG, templateValues));

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(
                MonitoringPaths.TPL_LOKI, MonitoringPaths.LOKI_CONFIG, templateValues));

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(
                MonitoringPaths.TPL_ALLOY, MonitoringPaths.ALLOY_CONFIG, templateValues));

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_BOOTSTRAP,
                MonitoringPaths.BOOTSTRAP_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.BOOTSTRAP_SCRIPT);
        commands.add(MonitoringPaths.BOOTSTRAP_SCRIPT);
        commands.add("mkdir -p /etc/grafana/provisioning/datasources");

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_DS_PROMETHEUS,
                "/etc/grafana/provisioning/datasources/prometheus.yaml", templateValues));
        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_DS_CLOUDWATCH,
                "/etc/grafana/provisioning/datasources/cloudwatch.yaml", templateValues));
        commands.addAll(ShellTemplateRenderer.writeToFileCommands(
                MonitoringPaths.TPL_DS_LOKI, "/etc/grafana/provisioning/datasources/loki.yaml", templateValues));

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_DASHBOARD,
                MonitoringPaths.DASHBOARD_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.DASHBOARD_SCRIPT);

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PINPOINT_UP,
                MonitoringPaths.PINPOINT_UP_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_UP_SCRIPT);

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PINPOINT_DOWN,
                MonitoringPaths.PINPOINT_DOWN_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_DOWN_SCRIPT);

        commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PINPOINT_STATUS,
                MonitoringPaths.PINPOINT_STAT_SCRIPT, templateValues));
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_STAT_SCRIPT);

        commands.add("cat <<'EOF' >" + MonitoringPaths.BASE_DIR + "/pinpoint-quickstart.txt");
        commands.add("Pinpoint ON  : sudo " + MonitoringPaths.PINPOINT_UP_SCRIPT);
        commands.add("Pinpoint OFF : sudo " + MonitoringPaths.PINPOINT_DOWN_SCRIPT);
        commands.add("Pinpoint STAT: sudo " + MonitoringPaths.PINPOINT_STAT_SCRIPT);
        commands.add("EOF");
        
        commands.add("if [ \"${MONITORING_REPROVISION_GRAFANA:-false}\" = \"true\" ]; then "
                + "systemctl restart " + grafanaConfig.grafanaServiceName() + "; "
                + "bash " + MonitoringPaths.DASHBOARD_SCRIPT + " || true; "
                + "else echo '[INFO] Skip Grafana reprovision'; fi");
        return commands;
    }

    /**
     * Monitoring bootstrap 파일을 로컬 디렉터리에 렌더링해서 CDK asset으로 업로드할 수 있게 준비한다.
     */
    public Path renderMonitoringBootstrapAsset(
            String region,
            String internalDomain,
            int adminApiPort,
            int customerApiPort,
            String mskBootstrapBrokersSaslIam
    ) {
        Map<String, String> templateValues = buildTemplateValues(
                region,
                internalDomain,
                adminApiPort,
                customerApiPort,
                mskBootstrapBrokersSaslIam
        );
        Path assetRoot = Path.of("build/generated/monitoring-bootstrap");

        recreateDirectory(assetRoot);

        writeRenderedFile(assetRoot, MonitoringPaths.PREPARE_HOST_SCRIPT, MonitoringPaths.TPL_PREPARE_HOST, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.PROMETHEUS_CONFIG, MonitoringPaths.TPL_PROMETHEUS, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.LOKI_CONFIG, MonitoringPaths.TPL_LOKI, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.ALLOY_CONFIG, MonitoringPaths.TPL_ALLOY, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.BOOTSTRAP_SCRIPT, MonitoringPaths.TPL_BOOTSTRAP, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.DASHBOARD_SCRIPT, MonitoringPaths.TPL_DASHBOARD, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.PINPOINT_UP_SCRIPT, MonitoringPaths.TPL_PINPOINT_UP, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.PINPOINT_DOWN_SCRIPT, MonitoringPaths.TPL_PINPOINT_DOWN, templateValues);
        writeRenderedFile(assetRoot, MonitoringPaths.PINPOINT_STAT_SCRIPT, MonitoringPaths.TPL_PINPOINT_STATUS, templateValues);

        writeRenderedFile(
                assetRoot,
                "/etc/grafana/provisioning/datasources/prometheus.yaml",
                MonitoringPaths.TPL_DS_PROMETHEUS,
                templateValues
        );
        writeRenderedFile(
                assetRoot,
                "/etc/grafana/provisioning/datasources/cloudwatch.yaml",
                MonitoringPaths.TPL_DS_CLOUDWATCH,
                templateValues
        );
        writeRenderedFile(
                assetRoot,
                "/etc/grafana/provisioning/datasources/loki.yaml",
                MonitoringPaths.TPL_DS_LOKI,
                templateValues
        );
        writeText(assetRoot, MonitoringPaths.BASE_DIR + "/install-monitoring.sh", buildInstallMonitoringScript());

        return assetRoot;
    }

    // 쉘 템플릿 치환에 사용할 값을 현재 설정과 실행 컨텍스트로 구성
    private Map<String, String> buildTemplateValues(
            String region,
            String internalDomain,
            int adminApiPort,
            int customerApiPort,
            String mskBootstrapBrokersSaslIam) {
        GrafanaConfig g = grafanaConfig;
        KafkaUiConfig k = kafkaUiConfig;
        PinpointConfig p = pinpointConfig;

        String adminApiMetricsTarget = adminApiServiceDnsLabel + "." + internalDomain + ":" + adminApiPort;
        String customerApiMetricsTarget = customerApiServiceDnsLabel + "." + internalDomain + ":"
                                          + customerApiPort;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("MONITORINGDIR", MonitoringPaths.BASE_DIR);
        values.put("SWAPSIZEGIB", String.valueOf(swapSizeGib));
        values.put("REGION", region);
        values.put("DBSECRETID", dbSecretId);
        values.put("DOCKERNETWORKNAME", dockerNetworkName);
        values.put("PGEXPORTERCONTAINERNAME", pgExporterContainerName);
        values.put("PROMETHEUSCONTAINERNAME", prometheusContainerName);
        values.put("PGEXPORTERIMAGE", pgExporterImage);
        values.put("PGEXPORTEREXCLUDEDATABASES", pgExporterExcludeDatabases);
        values.put("PROMETHEUSPORT", String.valueOf(prometheusPort));
        values.put("PROMETHEUSCONFIGPATH", MonitoringPaths.PROMETHEUS_CONFIG);
        values.put("PROMETHEUSIMAGE", prometheusImage);
        values.put("PROMETHEUSSCRAPEINTERVAL", prometheusScrapeInterval);
        values.put("PGEXPORTERPROMETHEUSJOBNAME", pgExporterPrometheusJobName);
        values.put("PGEXPORTERPORT", String.valueOf(pgExporterPort));
        values.put("KAFKAUICONTAINERNAME", k.containerName());
        values.put("KAFKAUIIMAGE", k.image());
        values.put("KAFKAUIPORT", String.valueOf(k.kafkaUiPort()));
        values.put("KAFKAUICLUSTERNAME", AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME));
        values.put("MSKBOOTSTRAPBROKERSSASLIAM", mskBootstrapBrokersSaslIam);
        values.put("ADMINAPIPROMETHEUSJOBNAME", adminApiPrometheusJobName);
        values.put("ADMINAPIMETRICSTARGET", adminApiMetricsTarget);
        values.put("CUSTOMERAPIPROMETHEUSJOBNAME", customerApiPrometheusJobName);
        values.put("CUSTOMERAPIMETRICSTARGET", customerApiMetricsTarget);
        values.put("GRAFANAPORT", String.valueOf(g.grafanaPort()));
        values.put("AUTODASHBOARDPANELLIMIT", String.valueOf(autoDashboardPanelLimit));
        values.put("GRAFANAADMINUSER", g.grafanaAdminUser());
        values.put("GRAFANAADMINPASSWORD", g.grafanaAdminPassword());
        values.put("PINPOINTREPOURL", p.pinpointRepoUrl());
        values.put("PINPOINTREPODIR", p.pinpointRepoDir());
        values.put("PINPOINTVERSION", p.pinpointVersion());
        values.put("PINPOINTGITREF", p.pinpointGitRef());

        values.put("LOKICONTAINERNAME", lokiConfig.lokiContainerName());
        values.put("LOKIIMAGE", lokiConfig.lokiImage());
        values.put("LOKIPORT", String.valueOf(lokiConfig.lokiPort()));
        values.put("LOKICONFIGPATH", MonitoringPaths.LOKI_CONFIG);
        values.put("LOKIS3BUCKET", lokiConfig.lokiS3Bucket());
        values.put("LOKIS3PREFIX", normalizeS3Prefix(lokiConfig.lokiS3Prefix()));

        //Loki
        values.put("LOKITRACEDEBUGRETENTIONHOURS", String.valueOf(lokiConfig.lokiTraceDebugRetentionHours()));
        values.put("LOKIWARNRETENTIONHOURS", String.valueOf(lokiConfig.lokiWarnRetentionHours()));
        values.put("LOKIERRORRETENTIONHOURS", String.valueOf(lokiConfig.lokiErrorRetentionHours()));
        values.put("LOKIFATALRETENTIONHOURS", String.valueOf(lokiConfig.lokiFatalRetentionHours()));

        //Alloy
        values.put("ALLOYCONTAINERNAME", alloyConfig.alloyContainerName());
        values.put("ALLOYIMAGE", alloyConfig.alloyImage());
        values.put("ALLOYCONFIGPATH", MonitoringPaths.ALLOY_CONFIG);
        values.put("ALLOYECSLOGGROUPSBLOCKS", buildAlloyLogGroupBlocksHcl());
        values.put("ALLOYLOGENV", alloyConfig.alloyLogEnv());

        return values;
    }

    /*
     * =================================================================
     *                              Loki Setup
     * =================================================================
     */

    public String lokiS3BucketArn() {
        return S3_PREFIX + lokiConfig.lokiS3Bucket();
    }

    //Loki가 S3 객체 접근 시 사용할 객체 레벨 ARN 생성
    public String lokiS3ObjectArn() {
        return S3_PREFIX + lokiConfig.lokiS3Bucket() + "/*";
    }

    //Alloy가 CloudWatch Log Group 읽을 수 있는 IAM 권한 부여
    public List<String> cloudWatchLogGroupArns(String region, String accountId) {
        List<String> arns = new ArrayList<>();

        for (String logGroup : parseFlat(alloyConfig.alloyEcsLogGroups())) {
            arns.add(String.format("arn:aws:logs:%s:%s:log-group:%s", region, accountId, logGroup));
            arns.add(String.format("arn:aws:logs:%s:%s:log-group:%s:*", region, accountId, logGroup));
        }
        return arns;
    }


    private String normalizeS3Prefix(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private List<String> parseFlat(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String buildAlloyLogGroupBlocksHcl() {
        List<String> groups = parseFlat(alloyConfig.alloyEcsLogGroups());
        if (groups.isEmpty()) {
            throw new IllegalStateException("MONITORING_ALLOY_ECS_LOG_GROUPS 값이 비어 있습니다.");
        }
        return groups.stream()
                .map(group -> "      named {\n        group_name = \"" + group.replace("\"", "\\\"") + "\"\n      }")
                .collect(Collectors.joining("\n"));
    }

    private String buildInstallMonitoringScript() {
        List<String> commands = new ArrayList<>();
        commands.add("#!/usr/bin/env bash");
        commands.addAll(grafanaUserDataCommands());
        commands.add("chmod +x " + MonitoringPaths.PREPARE_HOST_SCRIPT);
        commands.add(MonitoringPaths.PREPARE_HOST_SCRIPT);
        commands.add("chmod +x " + MonitoringPaths.BOOTSTRAP_SCRIPT);
        commands.add(MonitoringPaths.BOOTSTRAP_SCRIPT);
        commands.add("chmod +x " + MonitoringPaths.DASHBOARD_SCRIPT);
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_UP_SCRIPT);
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_DOWN_SCRIPT);
        commands.add("chmod +x " + MonitoringPaths.PINPOINT_STAT_SCRIPT);
        commands.add("cat <<'EOF' >" + MonitoringPaths.BASE_DIR + "/pinpoint-quickstart.txt");
        commands.add("Pinpoint ON  : sudo " + MonitoringPaths.PINPOINT_UP_SCRIPT);
        commands.add("Pinpoint OFF : sudo " + MonitoringPaths.PINPOINT_DOWN_SCRIPT);
        commands.add("Pinpoint STAT: sudo " + MonitoringPaths.PINPOINT_STAT_SCRIPT);
        commands.add("EOF");
        commands.add("if [ \"${MONITORING_REPROVISION_GRAFANA:-false}\" = \"true\" ]; then "
                + "systemctl restart " + grafanaConfig.grafanaServiceName() + "; "
                + "bash " + MonitoringPaths.DASHBOARD_SCRIPT + " || true; "
                + "else echo '[INFO] Skip Grafana reprovision'; fi");
        return String.join("\n", commands) + "\n";
    }

    private void recreateDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (var walk = Files.walk(directory)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException ex) {
                                    throw new IllegalStateException("Failed to clean monitoring asset directory: " + path, ex);
                                }
                            });
                }
            }
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare monitoring asset directory: " + directory, ex);
        }
    }

    private void writeRenderedFile(
            Path assetRoot,
            String targetPath,
            String templatePath,
            Map<String, String> templateValues
    ) {
        writeText(assetRoot, targetPath, ShellTemplateRenderer.renderTemplate(templatePath, templateValues));
    }

    private void writeText(Path assetRoot, String targetPath, String content) {
        Path relativeTarget = assetRoot.resolve(stripLeadingSlash(targetPath));
        try {
            Files.createDirectories(relativeTarget.getParent());
            Files.writeString(relativeTarget, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write monitoring bootstrap file: " + relativeTarget, ex);
        }
    }

    private String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
