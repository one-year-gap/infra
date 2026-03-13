package com.myorg.props;

import com.myorg.config.monitoring.MonitoringConfig;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;

public record MonitoringStackProps(
        Vpc vpc,
        SecurityGroup dbSg,
        SecurityGroup adminApiSg,
        SecurityGroup customerApiSg,
        SecurityGroup kafkaBrokerSg,
        String mskBootstrapBrokersSaslIam,
        int adminApiPort,
        int customerApiPort,
        MonitoringConfig config
) {
}
