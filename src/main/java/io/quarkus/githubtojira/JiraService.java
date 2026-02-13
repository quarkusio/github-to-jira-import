package io.quarkus.githubtojira;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import io.atlassian.fugue.Iterables;
import io.quarkus.githubtojira.model.JiraInfo;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.codehaus.jettison.json.JSONArray;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class JiraService {

    private JiraRestClient client;

    @ConfigProperty(name = "jira.server")
    String jiraServer;

    @ConfigProperty(name = "jira.token")
    String jiraToken;

    @ConfigProperty(name = "jira.project")
    String jiraProject;

    @ConfigProperty(name = "timeout")
    Duration timeout;

    @ConfigProperty(name = "jira.pull-request-field-id")
    String pullRequestFieldId;

    @ConfigProperty(name = "jira.issue-type-bug")
    Long issueTypeBug;

    @ConfigProperty(name = "jira.issue-type-component-upgrade")
    Long issueTypeComponentUpgrade;

    @ConfigProperty(name = "jira.issue-type-feature")
    Long issueTypeFeature;

    @ConfigProperty(name = "testing-run")
    Boolean testingRun;

    @ConfigProperty(name = "jira.assignee")
    String assignee;

    @ConfigProperty(name = "jira.transition-to-state")
    Integer transitionToState;

    final Pattern fixVersionPattern = Pattern.compile("(\\d+\\.\\d+)\\.\\d+\\.GA");

    @PostConstruct
    public void init() throws URISyntaxException {
        client = new AsynchronousJiraRestClientFactory().create(new URI(jiraServer),
                builder -> builder.setHeader("Authorization", "Bearer " + jiraToken));
    }

    public List<String> findExistingFixVersions() throws ExecutionException, InterruptedException {
        List<String> fixVersions = StreamSupport.stream(client.getProjectClient().getProject(jiraProject).get().getVersions().spliterator(), false)
                .map(version -> version.getName())
                .filter(version -> fixVersionPattern.matcher(version).matches())
                .sorted((o1, o2) -> -o1.compareTo(o2))
                .toList();
        return fixVersions;
    }

    public List<JiraInfo> findExistingJirasForPullRequests(List<String> prUrls, String fixVersion) throws Exception {
        // construct a query that looks like:
        // project = QUARKUS and fixVersion ~ "2.13*" and ("Git Pull Request" ~ "url1" or "Git Pull Request" ~ "url2" or ...)
        // (the 'Git Pull Request' field does not support the IN operator...)
        String prUrlsClause = "(" + prUrls.stream().map(url -> "\"Git Pull Request\" ~ \"" + url + "\"").collect(Collectors.joining(" or ")) + ")";
        String query = "project = " + jiraProject + " " +
                "and fixVersion ~ \"" + fixVersion + "\" " +
                "and " + prUrlsClause;
        Log.info("Jira query to find existing issues: " + query);
        int pageSize = 50;
        int page = 0;
        boolean hasNextPage = true;
        List<JiraInfo> result = new ArrayList<>();
        while(hasNextPage) {
            hasNextPage = false;
            SearchResult searchResult = client.getSearchClient()
                    .searchJql(query, pageSize, page * pageSize, null)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            int foundSoFar = 0;
            for (Issue issue : searchResult.getIssues()) {
                foundSoFar++;
                JiraInfo jiraInfo = new JiraInfo();
                jiraInfo.setKey(issue.getKey());
                jiraInfo.setUrl(jiraServer + "/browse/" + issue.getKey());
                JSONArray urlsAsJsonArray = (JSONArray) issue.getField(pullRequestFieldId).getValue();
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < urlsAsJsonArray.length(); i++) {
                    urls.add(urlsAsJsonArray.getString(i));
                }
                jiraInfo.setGitPullRequestUrls(urls);
                result.add(jiraInfo);
            }
            page++;
            if(foundSoFar == pageSize) {
                hasNextPage = true;
            }
        }
        return result;
    }

    // Convert a Quarkus version to a value of the fixVersion field in Jira
    public String fixVersionToJiraVersion(String fixVersion) {
        // hopefully this will be enough for the time being
        return fixVersion + ".GA";
    }

    // Convert a Quarkus version to a wildcard that can be used for filtering the fixVersion JIRA field
    // to find issues for the particular minor stream
    // for example, 3.27.1 is turned into 3.27.*
    // and we then later search in JIRA using this clause:
    //      and fixVersion ~ "3.27.*"
    public String fixVersionToJiraVersionMajorMinorWildcard(String fixVersion) {
        String[] parts = fixVersion.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1] + ".*";
        } else {
            return fixVersion + ".*";
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            client.close();
        } catch (Exception e) {
            // Ignore
        }
    }

    public String createJira(String prUrl, String prTitle, String fixVersion, String type, String description) throws Exception {
        long issueTypeId = switch (type) {
            case "bug" -> issueTypeBug;
            case "upgrade" -> issueTypeComponentUpgrade;
            case "feature" -> issueTypeFeature;
            default -> throw new IllegalArgumentException("Unknown issue type: " + type);
        };
        if (testingRun) {
            prTitle = "[TESTING, PLEASE IGNORE] " + prTitle;
            description = "IGNORE: I'm just testing a new JIRA import app\n\n " + description;
        }
        IssueInput input = new IssueInputBuilder()
                .setProjectKey(jiraProject)
                .setSummary(prTitle)
                .setIssueTypeId(issueTypeId)
                .setDescription(description)
                .setFieldValue(pullRequestFieldId, prUrl)
                .setFixVersionsNames(Iterables.iterable(fixVersion))
                .setComponentsNames(Iterables.iterable("team/eng"))
                .setAssigneeName(assignee)
                .build();
        Log.info("Issue input: " + input);
        BasicIssue issue = client.getIssueClient().createIssue(input).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Log.info("Created issue: " + jiraServer + "/browse/" + issue.getKey());
        if(transitionToState != 0) {
            client.getIssueClient().transition(client.getIssueClient().getIssue(issue.getKey()).get(),
                    new TransitionInput(transitionToState, Collections.emptySet()));
        }
        return jiraServer + "/browse/" + issue.getKey();
    }
}
