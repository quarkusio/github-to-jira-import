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

        public static native TemplateInstance index(List<ProjectInfo> projects);

        public static native TemplateInstance importing(Integer projectNumber,
                                                        String fixVersion,
                                                        List<PullRequestInfo> pullRequests);

    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    public TemplateInstance index() throws Exception {
        return Templates.index(gitHubService.getBackportProjectsMap(projectNamePattern));
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Blocking
    @Path("/importing/{projectNumber}/{fixVersion}")
    public TemplateInstance importing(Integer projectNumber, String fixVersion) throws Exception {
        List<PullRequestInfo> pullRequests = gitHubService.getPullRequestsBackportedToVersion(projectNumber, fixVersion);
        if (!pullRequests.isEmpty()) {
            List<JiraInfo> jiras = jiraService.findExistingJirasForPullRequests(
                    pullRequests.stream().map(PullRequestInfo::getUrl).toList(),
                    jiraService.fixVersionToJiraVersionMajorMinorWildcard(fixVersion));
            for (PullRequestInfo pullRequest : pullRequests) {
                pullRequest.setExistingJiras(new ArrayList<>());
                jiras.stream().filter(jira -> jira.getGitPullRequestUrls().contains(pullRequest.getUrl()))
                        .findFirst().ifPresent(jira -> {
                            Log.info("Linking existing jira " + jira.getUrl() + " to PR " + pullRequest.getUrl());
                            pullRequest.getExistingJiras().add(jira);
                        });
            }
        }
        pullRequests.forEach(pr -> {
            pullRequestCache.put(pr.getNumber(), pr);
        });
        return Templates.importing(projectNumber, fixVersion, pullRequests);
    }

    @GET
    @Path("/import/{prNumber}/{fixVersion}/{type}")
    public String performImport(Integer prNumber, String fixVersion, String type) throws Exception {
        PullRequestInfo pr = pullRequestCache.get(prNumber);
        if (pr == null) {
            throw new IllegalArgumentException("No PR with number " + prNumber + " found in the cache");
        }
        return jiraService.createJira(pr.getUrl(), pr.getTitle(), jiraService.fixVersionToJiraVersion(fixVersion), type, pr.getDescription());
    }

}
