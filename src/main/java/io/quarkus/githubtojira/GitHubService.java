package io.quarkus.githubtojira;

import io.atlassian.fugue.Iterables;
import io.quarkus.githubtojira.model.ProjectInfo;
import io.quarkus.githubtojira.model.PullRequestInfo;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.core.Document;
import io.smallrye.graphql.client.core.Operation;
import io.smallrye.graphql.client.core.OperationType;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class GitHubService {

    @Inject
    @GraphQLClient("github")
    DynamicGraphQLClient client;

    @ConfigProperty(name = "github.organization")
    private String organization;

    @ConfigProperty(name = "github.repository")
    private String repository;


    /**
     * Retrieves information about a pull request given the PR number.
     */
    public PullRequestInfo getPullRequestInfo(String prNumber, String repo) {
        String query = """
            query ($owner: String!, $name: String!, $number: Int!) {
                repository(owner: $owner, name: $name) {
                    pullRequest(number: $number) {
                        url
                        title
                        number
                        bodyText
                        labels(first:20) {
                            nodes {
                                name
                            }
                        }
                        changedFiles
                        additions
                        deletions
                        files(first: 50) {
                            nodes {
                                path
                            }
                        }
                    }
                }
            }
            """;
        String org = repo.split("/")[0];
        String repoName = repo.split("/")[1];
        Map<String, Object> args = Map.of("owner", org,
                "name", repoName,
                "number", Integer.parseInt(prNumber));
        try {
            Response response = client.executeSync(query, args);
            checkForErrors(response);
            JsonObject prData = response.getData().getJsonObject("repository").getJsonObject("pullRequest");
            PullRequestInfo prInfo = new PullRequestInfo();
            prInfo.setUrl(prData.getString("url"));
            prInfo.setTitle(prData.getString("title"));
            prInfo.setNumber(prData.getInt("number"));
            prInfo.setDescription(prData.getString("bodyText"));
            List<String> labels = prData.getJsonObject("labels").getJsonArray("nodes").stream()
                    .map(label -> label.asJsonObject().getString("name"))
                    .toList();
            Log.debug("PR labels: " + labels);

            int changedFiles = prData.getInt("changedFiles");
            int additions = prData.getInt("additions");
            int deletions = prData.getInt("deletions");
            List<String> files = prData.getJsonObject("files").getJsonArray("nodes").stream()
                    .map(label -> label.asJsonObject().getString("path"))
                    .toList();
            Log.debug("Counts:   changedFiles: " + changedFiles + "\tadditions: " + additions + "\tdeletions: " + deletions);
            Log.debug("Files: " + files);

            Iterable<String> jiraLabels = jiraIssuesCategorization(labels, files);
            Log.debug("JIRA labels: " + jiraLabels);

            prInfo.setLabels(labels);
            prInfo.setFiles(files);
            prInfo.setJiraLabels(jiraLabels);

            return prInfo;
        } catch (Exception e) {
            Log.error("Error fetching pull request info for PR #" + prNumber, e);
            return null;
        }
    }


    private Iterable<String> jiraIssuesCategorization(List<String> prLabels, List<String> prFiles) {

        if (checkLabelsAndPaths(prLabels, prFiles, List.of("area/documentation", "area/docstyle"),
                List.of("docs/src"), List.of(".adoc", ".md"))) {
            return Iterables.iterable("upstream-docs");
        }

        if (checkLabelsAndPaths(prLabels, prFiles, List.of("area/infra-automation"),
                List.of(".github", ".mvn/maven.config", "devtools/cli/distribution"), List.of())) {
            return Iterables.iterable("upstream-infra");
        }

        if (checkLabelsAndPaths(prLabels, prFiles, List.of("area/testing"),
                List.of("test-framework", "integration-tests"), List.of("TestCase.java", "Test.java"))) {
            return Iterables.iterable("upstream-testing");
        }

        return Iterables.emptyIterable();
    }

    private static boolean checkLabelsAndPaths(List<String> prLabels, List<String> prFiles, List<String> expectedLabels,
                                               List<String> expectedPathStarts, List<String> expectedPathEnds) {

        boolean prLabelsMatch = prLabels.stream()
                .filter(label -> !label.startsWith("triage"))
                .filter(label -> !label.startsWith("kind"))
                .allMatch(label -> expectedLabels.contains(label));
        boolean prFilesStartMatch = prFiles.stream()
                .allMatch(changedFile -> expectedPathStarts.stream()
                        .anyMatch(expectedPathStart -> changedFile.startsWith(expectedPathStart)));
        boolean prFilesEndMatch = prFiles.stream()
                .allMatch(changedFile -> expectedPathEnds.stream()
                        .anyMatch(expectedPathEnd -> changedFile.endsWith(expectedPathEnd)));

        return prLabelsMatch || prFilesStartMatch || prFilesEndMatch;
    }

    public List<ProjectInfo> getBackportProjectsMap(Pattern namePattern) throws Exception {
        String query = """
            query ($organization: String!) {
              organization(login: $organization) {
                   projectsV2(first: 100) {
                     nodes {
                       title
                       number
                       field(name: "Status") {
                         ... on ProjectV2SingleSelectField {
                           options {
                             name
                             id
                           }
                         }
                       }
                     }
                   }
                 }
            }
            """;
        Map<String, Object> args = Map.of("organization", organization);
        Response response = client.executeSync(query, args);
        Log.info("GraphQL response: " + response.getData());
        checkForErrors(response);
        AtomicInteger nullCounter = new AtomicInteger(0);
        JsonObject data = response.getData();
        if(data == null
                || data.getValueType() == JsonValue.ValueType.NULL
                || data.getJsonObject("organization").getJsonObject("projectsV2").getJsonArray("nodes").stream().allMatch(p -> p.getValueType() == JsonValue.ValueType.NULL)) {
            throw new RuntimeException("No project data received from GitHub. Does your GITHUB_TOKEN have the `project` permission and are you member of the " + organization + " organization?");
        }
        List<ProjectInfo> result = data.getJsonObject("organization").getJsonObject("projectsV2").getJsonArray("nodes")
            .stream()
            .filter(node -> {
                if (node.getValueType() == JsonValue.ValueType.NULL) {
                    nullCounter.incrementAndGet();
                    return false;
                } else {
                    return true;
                }
            })
            .filter(node -> node instanceof JsonObject && namePattern.matcher(node.asJsonObject().getString("title")).matches())
            .map(node -> {
                String title = node.asJsonObject().getString("title");
                int number = node.asJsonObject().getInt("number");
                ProjectInfo projectInfo = new ProjectInfo();
                projectInfo.setNumber(number);
                projectInfo.setTitle(title);
                JsonArray options = node.asJsonObject().getJsonObject("field").getJsonArray("options");
                projectInfo.setVersions(options.stream().map(option -> option.asJsonObject().getString("name")).toArray(String[]::new));
                Log.info("Found project: " + projectInfo);
                return projectInfo;
            })
            .sorted(Comparator.comparing(projectInfo1 -> -projectInfo1.getNumber()))
            .toList();

        if (nullCounter.get() > 0) {
            Log.warn(nullCounter.get() + " projects had to be ignored because it seems you miss the permissions to read them");
        }

        return result;
    }

    public List<PullRequestInfo> getPullRequestsBackportedToVersion(Integer projectNumber, String fixVersion) throws Exception {
        boolean hasNextPage = true;
        String endCursor = null;
        List<JsonValue> rawList = new ArrayList<>();
        while(hasNextPage) {
            String query = """
                query ($organization: String!, $projectNumber: Int!) {
                  organization(login: $organization) {
                     projectV2(number: $projectNumber) {
                       items(first: 100, after: %s) {
                         nodes {
                           STATUS:fieldValueByName(name: "Status") {
                             ... on ProjectV2ItemFieldSingleSelectValue {
                               FIXVERSION:name
                             }
                           }
                           content {
                             ... on PullRequest {
                               url
                               title
                               number
                               bodyText
                             }
                           }
                         }
                         pageInfo {
                           endCursor
                           hasNextPage
                         }
                       }
                     }
                  }
                }
                """.formatted(endCursor == null ? null : "\"" + endCursor + "\"");
            Map<String, Object> args = Map.of("organization", organization,
                    "projectNumber", projectNumber); // unused
            Response response = client.executeSync(query, args);
            Log.debug("Query: " + query);
            Log.debug("GraphQL response: " + response.getData());
            checkForErrors(response);
            JsonArray pullRequests = response.getData().getJsonObject("organization").getJsonObject("projectV2").getJsonObject("items").getJsonArray("nodes");

            rawList.addAll(pullRequests);
            endCursor = response.getData().getJsonObject("organization").getJsonObject("projectV2")
                    .getJsonObject("items")
                    .getJsonObject("pageInfo")
                    .getString("endCursor");
            hasNextPage = response.getData().getJsonObject("organization").getJsonObject("projectV2")
                    .getJsonObject("items")
                    .getJsonObject("pageInfo")
                    .getBoolean("hasNextPage");
        }
        List<PullRequestInfo> finalList = new ArrayList<>();

        for (JsonValue pullRequest : rawList) {
            // get only pull requests, because the query also returns issues
            if (pullRequest.asJsonObject().getJsonObject("content").get("url") != null) {
                String version = pullRequest.asJsonObject().getJsonObject("STATUS").getString("FIXVERSION", null);
                // get only pull requests targeting this fix version
                if (fixVersion.equals(version)) {
                    PullRequestInfo prInfo = new PullRequestInfo();
                    prInfo.setUrl(pullRequest.asJsonObject().getJsonObject("content").getString("url"));
                    prInfo.setTitle(pullRequest.asJsonObject().getJsonObject("content").getString("title"));
                    prInfo.setNumber(pullRequest.asJsonObject().getJsonObject("content").getInt("number"));
                    prInfo.setDescription(pullRequest.asJsonObject().getJsonObject("content").getString("bodyText"));
                    Log.info("Found pull request: " + prInfo);
                    finalList.add(prInfo);
                }
            }
        }
        Log.info("Total pull requests found: " + finalList.size());
        return finalList;
    }

    private void checkForErrors(Response response) {
        if(response.hasError()) {
            String errors = response.getErrors().stream().map(error -> error.toString()).collect(Collectors.joining());
            throw new RuntimeException(errors);
        }
    }

    public String getOrganization() {
        return organization;
    }

    public String getRepository() {
        return repository;
    }
}
