package com.myorg.stacks;

import com.myorg.config.RepositoryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ecr.Repository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EcrStackTest {

    @Test
    @DisplayName("ECR Repository는 2개 생성되어야 한다.")
    void should_create_ecr_repository_must_two() {
        //given
        App app = new App();
        EcrStack ecrStack = new EcrStack(app, "EcrStackTest", StackProps.builder().build());
        Template template = Template.fromStack(ecrStack);

        //when
        Map<String, Map<String, Object>> repos = template.findResources("AWS::ECR::Repository");

        //then
        //repo 2개 생성 검증
        assertEquals(2, repos.size());
        //api-server-test / admin-fe-test Repo가 존재하는지
        assertTrue(hasRepoName(repos, RepositoryConfig.getApiServerRepository()));
        assertTrue(hasRepoName(repos, RepositoryConfig.getAdminWebRepository()));
        //Retain(DeletionPolicy/UpdateReplacePolicy) 검증
        assertAllRetain(repos);

    }

    @SuppressWarnings("unchecked")
    private static boolean hasRepoName(Map<String, Map<String, Object>> repo, String repoName) {
        return repo.values().stream().anyMatch(obj -> {
            Map<String, Object> resource = (Map<String, Object>) obj;
            Map<String, Object> props = (Map<String, Object>) resource.get("Properties");

            return repoName.equals(props.get("RepositoryName"));
        });
    }

    @SuppressWarnings("unchecked")
    private static void assertAllRetain(Map<String, Map<String, Object>> repoResources) {
        repoResources.forEach((logicalId, obj) -> {
            Map<String, Object> resource = (Map<String, Object>) obj;

            assertEquals("Retain", resource.get("DeletionPolicy"));
            assertEquals("Retain", resource.get("UpdateReplacePolicy"));
        });
    }
}