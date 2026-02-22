package com.myorg.constants;

import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.iam.IManagedPolicy;
import software.amazon.awscdk.services.iam.ManagedPolicy;

public final class MonitoringConstants {
    private MonitoringConstants() {
    }

    /*
     * =================================================================
     *                              Spec
     * =================================================================
     */
    public static final String INSTANT_TYPE = "t3.small";
    public static final int ROOT_VOLUME_GIB = 30;
    public static final int GRAFANA_PORT = 3000;
    public static final int LOCAL_FORWARD_PORT = 13000;
    public static final String SUBNET_TYPE = "PRIVATE_WITH_EGRESS";

    /*
     * =================================================================
     *                              Policy
     * =================================================================
     */
    public static final String POLICY_NAME_INSTANCE_CORE = "AmazonSSMManagedInstanceCore";
    public static final String POLICY_NAME_CLOUD_WATCH = "CloudWatchReadOnlyAccess";
    public static final String POLICY_NAME_AWS_XRAY = "AWSXrayReadOnlyAccess";

    public static final IManagedPolicy POLICY_INSTANCE_CORE
            = ManagedPolicy.fromAwsManagedPolicyName(POLICY_NAME_INSTANCE_CORE);
    public static final IManagedPolicy POLICY_CLOUD_WATCH
            = ManagedPolicy.fromAwsManagedPolicyName(POLICY_NAME_CLOUD_WATCH);
    public static final IManagedPolicy POLICY_AWS_XRAY
            = ManagedPolicy.fromAwsManagedPolicyName(POLICY_NAME_AWS_XRAY);
}
