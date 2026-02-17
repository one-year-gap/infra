package com.myorg.props;

import com.myorg.constructs.FargateApiService;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.FargateService;

public record ApplicationLoadBalancerProps(
        Vpc vpc,
        SecurityGroup customerAlbSg,
        SecurityGroup adminAlbSg,

        FargateService customerApiService,
        int customerApiPort,

        FargateService adminWebService,
        int adminWebPort,

        ICertificate customerCert,
        ICertificate adminCert
) {
}
