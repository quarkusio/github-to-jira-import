package io.quarkus.githubtojira;

import io.quarkus.githubtojira.model.JiraInfo;
import io.quarkus.githubtojira.model.ProjectInfo;
import io.quarkus.githubtojira.model.PullRequestInfo;
import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Path("/")
@ApplicationScoped
public class GithubToJiraResource {

    @Inject
    GitHubService gitHubService;

    // used to filter the project names to only get projects related to backports
    private static Pattern projectNamePattern = Pattern.compile("Backports.+");

    @Inject
    JiraService jiraService;

    @ConfigProperty(name = "manual.imports.repos")
    List<String> reposForManualImports;

    private final Map<RepoAndPrNumber, PullRequestInfo> pullRequestCache = new HashMap<>();

    @CheckedTemplate
    public static class Templates {

        public static native TemplateInstance index(List<ProjectInfo> projects, List<String> jiraFixVersions, List<String> reposForManualImports);

        public static native TemplateInstance importing(Integer projectNumber,
                                                        String githubFixVersion,
                                                        List<PullRequestInfo> pullRequests,
                                                        String jiraFixVersion);

    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public TemplateInstance index() throws Exception {
        return Templates.index(gitHubService.getBackportProjectsMap(projectNamePattern), jiraService.findExistingFixVersions(), reposForManualImports);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/pr-metadata/{prNumber}/{repo}")
    public PullRequestInfo getPullRequestMetadata(String prNumber, String repo) {
        PullRequestInfo pr = gitHubService.getPullRequestInfo(prNumber, repo);
        pullRequestCache.put(new RepoAndPrNumber(repo, pr.getNumber()), pr);
        return pr;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    @Path("/importing/{projectNumber}/{githubFixVersion}/{jiraFixVersion}")
    public TemplateInstance importing(Integer projectNumber, String githubFixVersion, String jiraFixVersion) throws Exception {
        List<PullRequestInfo> pullRequests = gitHubService.getPullRequestsBackportedToVersion(projectNumber, githubFixVersion);
        if (!pullRequests.isEmpty()) {
            List<JiraInfo> jiras = jiraService.findExistingJirasForPullRequests(
                    pullRequests.stream().map(PullRequestInfo::getUrl).toList(),
                    jiraService.fixVersionToJiraVersionMajorMinorWildcard(githubFixVersion));
            for (PullRequestInfo pullRequest : pullRequests) {
                pullRequest.setExistingJiras(new ArrayList<>());
                jiras.stream().filter(jira -> jira.getGitPullRequestUrls().contains(pullRequest.getUrl()))
                        .forEach(jira -> {
                            Log.info("Linking existing jira " + jira.getUrl() + " to PR " + pullRequest.getUrl());
                            pullRequest.getExistingJiras().add(jira);
                        });
            }
        }
        pullRequests.forEach(pr -> {
            pullRequestCache.put(new RepoAndPrNumber(gitHubService.getOrganization() + "/" + gitHubService.getRepository(), pr.getNumber()), pr);
        });
        return Templates.importing(projectNumber, githubFixVersion, pullRequests, jiraFixVersion);
    }

    @GET
    @Path("/import/{repo}/{prNumber}/{jiraFixVersion}/{type}")
    public String performImport(String repo, Integer prNumber, String jiraFixVersion, String type) throws Exception {
        PullRequestInfo pr = pullRequestCache.get(new RepoAndPrNumber(repo, prNumber));
        if (pr == null) {
            throw new IllegalArgumentException("No PR with number " + prNumber + " found in the cache");
        }
        return jiraService.createJira(pr.getUrl(), pr.getTitle(), jiraFixVersion, type, pr.getDescription());
    }

    private record RepoAndPrNumber(String repo, Integer prNumber) {

    }

}
