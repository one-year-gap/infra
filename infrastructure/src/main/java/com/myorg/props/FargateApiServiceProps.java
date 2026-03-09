package com.myorg.props;

import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.servicediscovery.INamespace;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public record FargateApiServiceProps(
        Construct scope,
        String id,

        Cluster cluster,
        Repository repository,
        String imageTag,

        SecurityGroup serviceSg, //서비스 ENI에 붙는 SG
        int containerPort,

        LogGroup logGroup,//CloudWatch Logs 그룹
        String logStreamPrefix,//service별 로그 스트림

        SubnetSelection subnets,//Task 배치 subnet
        int desiredCount,
        boolean enableEcsExec,

        String springProfile,//spring profile = customer,admin
        String jdbcUrl,
        Secret dbSecret,
        // 서비스별 내부 DNS나 추가 런타임 설정은 공통 base env 뒤에 합쳐서 주입한다.
        Map<String, String> extraEnvironment,

        //Cloud Map
        INamespace cloudMapNamespace,
        String cloudMapServiceName,

        List<String> secretsManagerArns, //Secrets Manager ARN
        List<PolicyStatement> extraExecutionPolicies, //ExecutionRole 권한
        List<PolicyStatement> extraTaskPolicies //TaskRole 추가 권한
) {
}
