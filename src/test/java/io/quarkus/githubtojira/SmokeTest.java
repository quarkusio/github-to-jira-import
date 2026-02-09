package io.quarkus.githubtojira;

import io.quarkus.githubtojira.model.JiraInfo;
import io.quarkus.githubtojira.model.ProjectInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class SmokeTest {

    @Inject
    GitHubService gitHubService;

    @Inject
    JiraService jiraService;

    @Test
    public void testGithubConnection() throws Exception {
        List<ProjectInfo> projectsMap = gitHubService.getBackportProjectsMap(Pattern.compile("Backports.+"));
        assertThat(projectsMap).isNotEmpty();
    }

    @Test
    public void testGithubVersionToJiraFixVersion() {
        assertThat(jiraService.fixVersionToJiraVersion("3.20.4")).isEqualTo("3.20.4.GA");
    }

    @Test
    public void testFindExistingJirasForPullRequest() throws Exception {
        String fixVersionWildcard = jiraService.fixVersionToJiraVersionMajorMinorWildcard("3.20.4");
        assertThat(fixVersionWildcard).isEqualTo("3.20.*");
        List<JiraInfo> existingJiras = jiraService.findExistingJirasForPullRequests(List.of("https://github.com/quarkusio/quarkus/pull/49874"), fixVersionWildcard);
        assertThat(existingJiras).hasSize(1);
        JiraInfo existingJira = existingJiras.get(0);
        assertThat(existingJira.getKey()).isEqualTo("QUARKUS-6834");
        assertThat(existingJira.getUrl()).isEqualTo("https://issues.redhat.com/browse/QUARKUS-6834");
        assertThat(existingJira.getGitPullRequestUrls()).containsExactly("https://github.com/quarkusio/quarkus/pull/49874");
    }

}
