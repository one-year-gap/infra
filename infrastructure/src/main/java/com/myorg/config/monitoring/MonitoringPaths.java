package com.myorg.config.monitoring;

/**
 * Monitoring 경로 클래스
 */
public final class MonitoringPaths {
    public static final String BASE_DIR = "/opt/monitoring";
    public static final String PREPARE_HOST_SCRIPT = BASE_DIR + "/prepare-host.sh";
    public static final String PROMETHEUS_CONFIG = BASE_DIR + "/prometheus.yml";
    public static final String BOOTSTRAP_SCRIPT = BASE_DIR + "/bootstrap-monitoring.sh";
    public static final String DASHBOARD_SCRIPT = BASE_DIR + "/provision-dashboards.sh";
    public static final String PINPOINT_UP_SCRIPT = BASE_DIR + "/pinpoint-up.sh";
    public static final String PINPOINT_DOWN_SCRIPT = BASE_DIR + "/pinpoint-down.sh";
    public static final String PINPOINT_STAT_SCRIPT = BASE_DIR + "/pinpoint-status.sh";

    // classpath 템플릿 경로
    public static final String TPL_PREPARE_HOST = "grafana/prepare-host.sh.template";
    public static final String TPL_PROMETHEUS = "grafana/prometheus.yml.template";
    public static final String TPL_BOOTSTRAP = "grafana/bootstrap-monitoring.sh.template";
    public static final String TPL_DS_PROMETHEUS = "grafana/datasource-prometheus.yaml.template";
    public static final String TPL_DS_CLOUDWATCH = "grafana/datasource-cloudwatch.yaml.template";
    public static final String TPL_DASHBOARD = "grafana/provision-dashboards.sh.template";
    public static final String TPL_PINPOINT_UP = "pinpoint/pinpoint-up.sh.template";
    public static final String TPL_PINPOINT_DOWN = "pinpoint/pinpoint-down.sh.template";
    public static final String TPL_PINPOINT_STATUS = "pinpoint/pinpoint-status.sh.template";

    private MonitoringPaths() {
    }

}
