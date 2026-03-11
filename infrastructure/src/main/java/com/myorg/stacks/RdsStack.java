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
import java.util.Map;

public class RdsStack extends Stack {
    private final Secret dbSecret;
    private final DatabaseInstance rds;
    private final SecurityGroup dbSg;

    private static final String DB_NAME = "holliverse";
    private static final int DB_PORT = 5432;

    /**
     * private subnet PostgreSQL 인스턴스 구성.
     */
    public RdsStack(Construct scope, String id, StackProps props, Vpc vpc, SecurityGroup dbSg) {
        super(scope, id, props);

        // DB 시크릿 생성
        this.dbSecret = Secret.Builder.create(this, "HolliverseDbSecret")
                .secretName("holliverse/rds/postgres")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"holliverse\"}")
                        .generateStringKey("password")
                        .includeSpace(false)
                        .excludeCharacters("/@\" ")
                        .build())
                .build();

        this.dbSg = dbSg;

        // DB subnet 선택
        SubnetSelection dbSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();

        // PostgreSQL 엔진 선택
        IInstanceEngine postgresEngine = DatabaseInstanceEngine.postgres(
                PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_16)
                        .build()
        );

        // 파라미터 그룹 구성
        ParameterGroup postgresParameterGroup = ParameterGroup.Builder.create(this, "HolliversePostgresParameterGroup")
                .engine(postgresEngine)
                .description("PostgreSQL settings for slow query analysis with pg_stat_statements")
                .parameters(Map.of(
                        "shared_preload_libraries", "pg_stat_statements",
                        "pg_stat_statements.track", "all"
                ))
                .build();

        // RDS 인스턴스 생성
        this.rds = DatabaseInstance.Builder.create(this, "HolliversePostgres")
                .engine(postgresEngine)
                .vpc(vpc)
                .vpcSubnets(dbSubnets)
                .securityGroups(List.of(dbSg))
                .iamAuthentication(true)
                .credentials(Credentials.fromSecret(dbSecret))
                .databaseName(DB_NAME)
                .port(DB_PORT)
                .parameterGroup(postgresParameterGroup)
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
