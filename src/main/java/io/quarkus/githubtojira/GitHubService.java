package io.quarkus.githubtojira;

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
    String organization;

    @ConfigProperty(name = "github.repository")
    String repository;


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
        AtomicInteger nullCounter = new AtomicInteger(0);
        List<ProjectInfo> result = response.getData().getJsonObject("organization").getJsonObject("projectsV2").getJsonArray("nodes")
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
        String query = """
                query ($organization: String!, $projectNumber: Int!) {
                  organization(login: $organization) {
                     projectV2(number: $projectNumber) {
                       items {
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
                      }
                    }
                  }
                }
                """;
        Map<String, Object> args = Map.of("organization", organization,
                                          "projectNumber", projectNumber); // unused
        Response response = client.executeSync(query, args);
        Log.info("GraphQL response: " + response.getData());
        JsonArray pullRequests = response.getData().getJsonObject("organization").getJsonObject("projectV2").getJsonObject("items").getJsonArray("nodes");

        List<PullRequestInfo> list = new ArrayList<>();
        for (JsonValue pullRequest : pullRequests) {
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
                    list.add(prInfo);
                }
            }
        }
        return list;
    }

}
