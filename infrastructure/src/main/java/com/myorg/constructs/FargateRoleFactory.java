package com.myorg.constructs;

import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

import java.util.List;

public final class FargateRoleFactory {
    private FargateRoleFactory() {
    }

     /**
     * Execution Role: ECS/Fargate 런타임이 Task 시작 시 필요 권한
     */
    public static Role createExecutionRole(Construct scope, String id, List<PolicyStatement> extras) {
        Role role = Role.Builder.create(scope, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")
                ))
                .build();

        addExtraPolicies(role, extras);
        return role;
    }

    /**
     * Basic Task Role: service가 AWS 호출시 필요 권한
     */
    public static Role createBasicTaskRole(Construct scope, String id, List<PolicyStatement> extras) {
        Role role = Role.Builder.create(scope, id)
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();
        addExtraPolicies(role, extras);
        return role;
    }

    /*
     * Task Role with ECS Exec
     */
    public static Role createTaskRoleWithExec(Construct scope, String id, List<PolicyStatement> extras) {
        Role role = createBasicTaskRole(scope,id, extras);

        role.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "ssmmessages:CreateControlChannel",
                        "ssmmessages:CreateDataChannel",
                        "ssmmessages:OpenControlChannel",
                        "ssmmessages:OpenDataChannel"
                ))
                .resources(List.of("*"))
                .build());

        return role;
    }

    private static void addExtraPolicies(Role role, List<PolicyStatement> extraPolicies) {
        if (extraPolicies == null || extraPolicies.isEmpty()) {
            return;
        }
        extraPolicies.forEach(role::addToPolicy);
    }
}
