package com.myorg.stacks;


import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.efs.*;
import software.constructs.Construct;

/**
 * AWS EFS Stack
 */
public class EfsStack extends Stack {
    private final FileSystem fileSystem;
    private final AccessPoint accessPoint;
    private final SecurityGroup efsSg;

    public EfsStack(Construct scope, String id, StackProps props, String vpcId, String monitoringSecurityGroupId) {
        super(scope, id, props);

        IVpc vpc = Vpc.fromLookup(
                this,
                "ExistingVpcForEfs",
                VpcLookupOptions.builder()
                        .vpcId(vpcId)
                        .build()
        );

        // EFS 전용 Security Group
        this.efsSg = SecurityGroup.Builder.create(this, "AnalysisEfsSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .description("Analysis EFS Security Group")
                .build();

        //Monitoring EC2의 Security Group ID import
        ISecurityGroup monitoringEc2SecurityGroup = SecurityGroup.fromSecurityGroupId(
                this,
                "ImportedMonitoringEc2SecurityGroup",
                monitoringSecurityGroupId
        );

        //Monitoring Security Group -> EFS Security Group NFS(2049) Inbound 허용
        this.efsSg.addIngressRule(
                Peer.securityGroupId(monitoringEc2SecurityGroup.getSecurityGroupId()),
                Port.tcp(2049),
                "Allow NFS from Monitoring EC2 Security Group"
        );

        //AWS EFS 생성
        this.fileSystem = FileSystem.Builder.create(this, "AnalysisEfs")
                .vpc(vpc)
                .securityGroup(efsSg)
                .encrypted(true)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                        .build())
                .lifecyclePolicy(LifecyclePolicy.AFTER_14_DAYS)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        //Access Point
        this.accessPoint = AccessPoint.Builder.create(this, "AnalysisEfsAp")
                .fileSystem(fileSystem)
                .path("/analysis")
                .createAcl(Acl.builder()
                        .ownerUid("1000")
                        .ownerGid("1000")
                        .permissions("750")
                        .build())
                .posixUser(PosixUser.builder()
                        .uid("1000")
                        .gid("1000")
                        .build())
                .build();

        //테스트 검증용 출력
        CfnOutput.Builder.create(this, "AnalysisEfsFileSystemId")
                .value(fileSystem.getFileSystemId())
                .description("EFS FileSystem ID")
                .build();
        CfnOutput.Builder.create(this, "AnalysisEfsAccessPointId")
                .value(accessPoint.getAccessPointId())
                .description("EFS AccessPoint ID (/analysis)")
                .build();
    }

    public FileSystem getFileSystem() {
        return fileSystem;
    }

    public AccessPoint getAccessPoint() {
        return accessPoint;
    }
}
