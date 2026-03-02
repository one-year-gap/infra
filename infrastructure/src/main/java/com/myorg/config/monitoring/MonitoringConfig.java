package com.myorg.config.monitoring;

import com.myorg.builder.ShellTemplateRenderer;
import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SubnetType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monitoring Infra 공통 설정
 * - EC2 스펙, 네트워크, SSM, DB Secret, Prometheus/PgExporter 설정
 * - Grafana 상세 설정: {@link GrafanaConfig}
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

                // 세부 설정 (분리된 config)
                GrafanaConfig grafanaConfig,
                PinpointConfig pinpointConfig) {


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
                                PinpointConfig.fromEnv());
        }

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
                        int customerApiPort) {
                Map<String, String> templateValues = buildTemplateValues(region, internalDomain, adminApiPort,
                                customerApiPort);

                List<String> commands = new ArrayList<>();
                commands.add("mkdir -p " + MonitoringPaths.BASE_DIR);

                commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PREPARE_HOST,
                                MonitoringPaths.PREPARE_HOST_SCRIPT, templateValues));
                commands.add("chmod +x " + MonitoringPaths.PREPARE_HOST_SCRIPT);
                commands.add(MonitoringPaths.PREPARE_HOST_SCRIPT);

                commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_PROMETHEUS,
                                MonitoringPaths.PROMETHEUS_CONFIG, templateValues));

                commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_BOOTSTRAP,
                                MonitoringPaths.BOOTSTRAP_SCRIPT, templateValues));
                commands.add("chmod +x " + MonitoringPaths.BOOTSTRAP_SCRIPT);
                commands.add(MonitoringPaths.BOOTSTRAP_SCRIPT);

                commands.add("mkdir -p /etc/grafana/provisioning/datasources");
                commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_DS_PROMETHEUS,
                                "/etc/grafana/provisioning/datasources/prometheus.yaml", templateValues));
                commands.addAll(ShellTemplateRenderer.writeToFileCommands(MonitoringPaths.TPL_DS_CLOUDWATCH,
                                "/etc/grafana/provisioning/datasources/cloudwatch.yaml", templateValues));

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
                commands.add("systemctl restart " + grafanaConfig.grafanaServiceName());
                commands.add("bash " + MonitoringPaths.DASHBOARD_SCRIPT + " || true");
                return commands;
        }

        // 쉘 템플릿 치환에 사용할 값을 현재 설정과 실행 컨텍스트로 구성
        private Map<String, String> buildTemplateValues(
                        String region,
                        String internalDomain,
                        int adminApiPort,
                        int customerApiPort) {
                GrafanaConfig g = grafanaConfig;
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
                return values;
        }
}
