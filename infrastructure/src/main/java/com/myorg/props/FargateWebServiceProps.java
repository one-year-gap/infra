package com.myorg.props;

import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.logs.LogGroup;
import software.constructs.Construct;

public record FargateWebServiceProps(
        Construct scope,
        String id,

        Cluster cluster,
        Repository repository,
        String imageTag,//Repo에서 어떤 태그 이미지를 가져올지

        SecurityGroup serviceSg,
        int containerPort,

        LogGroup logGroup,//CloudWatch Logs로 보내기 위한 LogGroup
        String logStreamPrefix,//서비스별 구분용 prefix

        SubnetSelection subnets,
        int desiredCount, //유지할 Task 개수
        boolean enableEcsExec//AWS ECS exectute-command 사용 여부
) {
}
