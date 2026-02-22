package com.myorg.config;

import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SubnetType;

import java.util.List;
import java.util.Locale;

/**
 * Monitoring Infra Configuration
 */
public record MonitoringConfig(
        //EC2 타입
        String instanceType,
        //Disk size
        int rootVolumeGib,
        int grafanaPort,
        int localForwardPort,
        String subnetType,

        //Grafana RPM repo
        String grafanaRepoName,
        //Grafana RPM repo URL
        String grafanaRepoBaseUrl,
        //Grafana repo GPG key
        String grafanaRepoGpgKeyUrl,
        String grafanaPackageName,
        String grafanaServiceName,

        //SSM 포트포워딩 문서명
        String ssmPortForwardDocument,

        //Grafana EC2가 조회할 RDS Secret 이름 prefix
        String dbSecretNamePrefix
) {
    public static MonitoringConfig fromEnv() {
        return new MonitoringConfig(
                AppConfig.getOptionalValueOrDefault("MONITORING_INSTANCE_TYPE", "t3.small"),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault("MONITORING_ROOT_VOLUME_GIB", "30")),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_PORT", "3000")),
                Integer.parseInt(AppConfig.getOptionalValueOrDefault("MONITORING_LOCAL_FORWARD_PORT", "13000")),
                AppConfig.getOptionalValueOrDefault("MONITORING_SUBNET_TYPE", "PRIVATE_WITH_EGRESS"),
                AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_REPO_NAME", "grafana"),
                AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_REPO_BASE_URL", "https://rpm.grafana.com"),
                AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_REPO_GPG_KEY_URL", "https://rpm.grafana.com/gpg.key"),
                AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_PACKAGE_NAME", "grafana"),
                AppConfig.getOptionalValueOrDefault("MONITORING_GRAFANA_SERVICE_NAME", "grafana-server"),
                AppConfig.getOptionalValueOrDefault("MONITORING_SSM_PORT_FORWARD_DOCUMENT", "AWS-StartPortForwardingSession"),
                AppConfig.getOptionalValueOrDefault("MONITORING_DB_SECRET_NAME_PREFIX", "holliverse/rds/postgres")
        );
    }

    public InstanceType toInstanceType() {
        String[] parts = instanceType.trim().toLowerCase(Locale.ROOT).split("\\.");

        if (parts.length != 2) {
            throw new IllegalStateException("INSTANCE_TYPE 형식이 잘못되었습니다.");
        }

        InstanceClass instanceClass =
                switch (parts[0]) {
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

    public SubnetType toSubnetType() {
        try {
            return SubnetType.valueOf(subnetType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("지원하지 않는 SUBNET_TYPE: " + subnetType, ex);
        }
    }

    /**
     * Grafana 리포 등록
     */
    public List<String> grafanaUserDataCommands() {
        return List.of(
                "set -euxo pipefail",
                "cat <<'EOF' >/etc/yum.repos.d/" + grafanaRepoName + ".repo",
                "[" + grafanaRepoName + "]",
                "name=" + grafanaRepoName,
                "baseurl=" + grafanaRepoBaseUrl,
                "repo_gpgcheck=1",
                "enabled=1",
                "gpgcheck=1",
                "gpgkey=" + grafanaRepoGpgKeyUrl,
                "sslverify=1",
                "sslcacert=/etc/pki/tls/certs/ca-bundle.crt",
                "EOF",
                "dnf install -y " + grafanaPackageName,
                "sed -i -E 's|^;?allow_sign_up\\s*=\\s*true|allow_sign_up = false|' /etc/grafana/grafana.ini",
                "echo 'allow_sign_up = false' >> /etc/grafana/grafana.ini",
                "systemctl daemon-reload",
                "systemctl enable --now " + grafanaServiceName
        );
    }

    //SSM start-session Parameter 삽입 Json
    public String ssmPortForwardParametersJson() {
        return String.format("{\"portNumber\":[\"%d\"],\"localPortNumber\":[\"%d\"]}", grafanaPort, localForwardPort);
    }

    public String dbSecretArnPattern(String region, String accountId) {
        return String.format(
                "arn:aws:secretsmanager:%s:%s:secret:%s*",
                region,
                accountId,
                dbSecretNamePrefix
        );
    }

}
