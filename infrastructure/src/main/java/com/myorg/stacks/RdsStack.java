package com.myorg.stacks;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

public class RdsStack extends Stack {
    private final Secret dbSecret;
    private final DatabaseInstance rds;
    private final SecurityGroup dbSg;

    public RdsStack(Construct scope, String id, StackProps props, Vpc vpc, SecurityGroup customerApiSg, SecurityGroup adminApiSg){
        super(scope,id,props);





    }
}
