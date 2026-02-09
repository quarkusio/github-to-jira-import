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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final Map<Integer, PullRequestInfo> pullRequestCache = new HashMap<>();

    @CheckedTemplate
    public static class Templates {

        public static native TemplateInstance index(List<ProjectInfo> projects, List<String> jiraFixVersions);

        public static native TemplateInstance importing(Integer projectNumber,
                                                        String githubFixVersion,
                                                        List<PullRequestInfo> pullRequests,
                                                        String jiraFixVersion);

    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public TemplateInstance index() throws Exception {
        return Templates.index(gitHubService.getBackportProjectsMap(projectNamePattern), jiraService.findExistingFixVersions());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/pr-metadata/{prNumber}")
    public PullRequestInfo getPullRequestMetadata(String prNumber) {
        PullRequestInfo pr = gitHubService.getPullRequestInfo(prNumber);
        pullRequestCache.put(pr.getNumber(), pr);
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
            pullRequestCache.put(pr.getNumber(), pr);
        });
        return Templates.importing(projectNumber, githubFixVersion, pullRequests, jiraFixVersion);
    }

    @GET
    @Path("/import/{prNumber}/{jiraFixVersion}/{type}")
    public String performImport(Integer prNumber, String jiraFixVersion, String type) throws Exception {
        PullRequestInfo pr = pullRequestCache.get(prNumber);
        if (pr == null) {
            throw new IllegalArgumentException("No PR with number " + prNumber + " found in the cache");
        }
        return jiraService.createJira(pr.getUrl(), pr.getTitle(), jiraFixVersion, type, pr.getDescription());
    }

}
