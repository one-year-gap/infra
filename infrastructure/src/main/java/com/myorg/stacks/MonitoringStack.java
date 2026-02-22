package com.myorg.stacks;

import com.myorg.config.MonitoringConfig;
import com.myorg.constants.MonitoringConstants;
import com.myorg.constants.NetworkConstants;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

import java.util.List;


/**
 * Grafana Monitoring stack
 */
public class MonitoringStack extends Stack {
    private final Instance grafanaInstance;

    public MonitoringStack(
            Construct scope,
            String id,
            StackProps props,
            Vpc vpc,
            SecurityGroup dbSg,
            SecurityGroup adminApiSg,
            SecurityGroup customerApiSg,
            int adminApiPort,
            int customerApiPort,
            MonitoringConfig config
    ) {
        super(scope, id, props);

        SecurityGroup grafanaSg = SecurityGroup.Builder.create(this, "MonitoringSg")
                .vpc(vpc)
                .description("Grafana Security Group")
                .allowAllOutbound(true)
                .build();

        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToDbIngress")
                .groupId(dbSg.getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(NetworkConstants.PORT_POSTGRES)
                .toPort(NetworkConstants.PORT_POSTGRES)
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to DB PostgreSQL")
                .build();
        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToAdminApiIngress")
                .groupId(adminApiSg.getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(adminApiPort)
                .toPort(adminApiPort)
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to Admin API Actuator")
                .build();
        CfnSecurityGroupIngress.Builder.create(this, "GrafanaToCustomerApiIngress")
                .groupId(customerApiSg.getSecurityGroupId())
                .ipProtocol("tcp")
                .fromPort(customerApiPort)
                .toPort(customerApiPort)
                .sourceSecurityGroupId(grafanaSg.getSecurityGroupId())
                .description("Grafana to Customer API Actuator")
                .build();

        Role grafanaRole = Role.Builder.create(this, "GrafanaEc2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        MonitoringConstants.POLICY_INSTANCE_CORE,
                        MonitoringConstants.POLICY_CLOUD_WATCH,
                        MonitoringConstants.POLICY_AWS_XRAY
                ))
                .build();
        grafanaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "secretsmanager:GetSecretValue",
                        "secretsmanager:DescribeSecret"
                ))
                .resources(List.of(config.dbSecretArnPattern(this.getRegion(), this.getAccount())))
                .build());

        UserData userData = UserData.forLinux();
        userData.addCommands(config.grafanaUserDataCommands().toArray(String[]::new));

        this.grafanaInstance = Instance.Builder.create(this, "GrafanaServer")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(config.toSubnetType())
                        .build())
                .instanceType(config.toInstanceType())
                .machineImage(MachineImage.latestAmazonLinux2023())
                .securityGroup(grafanaSg)
                .role(grafanaRole)
                .userData(userData)
                .blockDevices(List.of(
                        BlockDevice.builder()
                                .deviceName("/dev/xvda")
                                .volume(BlockDeviceVolume.ebs(
                                        config.rootVolumeGib(),
                                        EbsDeviceOptions.builder()
                                                .volumeType(EbsDeviceVolumeType.GP3)
                                                .encrypted(true)
                                                .build()
                                ))
                                .build()
                ))
                .build();

        CfnOutput.Builder.create(this,"GrafanaInstanceId")
                .value(grafanaInstance.getInstanceId())
                .description("SSM target instance Id")
                .build();

        CfnOutput.Builder.create(this,"GrafanaPortForward")
                .value("aws ssm start-session --target " + grafanaInstance.getInstanceId()
                        + " --document-name " + config.ssmPortForwardDocument()
                        + " --parameters '" + config.ssmPortForwardParametersJson() + "'")
                .description("Port forward command for local Grafana access")
                .build();
    }

    public Instance getGrafanaInstance(){
        return grafanaInstance;
    }
}
