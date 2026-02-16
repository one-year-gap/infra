package com.myorg.stacks;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

import java.util.List;

public class RdsStack extends Stack {
    private final Secret dbSecret;
    private final DatabaseInstance rds;
    private final SecurityGroup dbSg;

    public RdsStack(Construct scope, String id, StackProps props, Vpc vpc, SecurityGroup customerApiSg, SecurityGroup adminApiSg) {
        super(scope, id, props);

        //Database SecretManager
        this.dbSecret = Secret.Builder.create(this, "HolliverseDbSecret")
                .secretName("holliverse/rds/postgres")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"holliverse\"}")
                        .generateStringKey("password")
                        .includeSpace(false)
                        .build())
                .build();

        //Database SecurityGroup
        this.dbSg = SecurityGroup.Builder.create(this, "HolliverseDbSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .description("Database SecurityGroup: allow 5432 from API Server")
                .build();

        //inbound: API/SSM -> DB
        dbSg.addIngressRule(customerApiSg, Port.tcp(5432), "Customer API -> DB");
        dbSg.addIngressRule(adminApiSg, Port.tcp(5432), "Admin API -> DB");

        //outbound
        customerApiSg.addEgressRule(dbSg, Port.tcp(5432), "Customer API -> DB 5432");
        adminApiSg.addEgressRule(dbSg, Port.tcp(5432), "Admin API -> DB 5432");

        SubnetSelection dbSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();

        this.rds = DatabaseInstance.Builder.create(this, "HolliversePostgres")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_16).build()))
                .vpc(vpc)
                .vpcSubnets(dbSubnets)
                .securityGroups(List.of(dbSg))
                .credentials(Credentials.fromSecret(dbSecret))
                .databaseName("holliverse")
                .port(5432)
                .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO)) //t4g.micro model
                .allocatedStorage(20)//storage 20GB
                .maxAllocatedStorage(20)
                .multiAz(false) //고가용성 사용 X
                .publiclyAccessible(false) //외부 접근 X
                .backupRetention(Duration.days(1))//backup 기준 1일
                .deletionProtection(true)
                .deleteAutomatedBackups(false)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    public DatabaseInstance getRds() {
        return rds;
    }

    public Secret getDbSecret() {
        return dbSecret;
    }

    public SecurityGroup getDbSg() {
        return dbSg;
    }
}
