package io.quarkus.githubtojira;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Base64;
import java.util.regex.Pattern;

public class StartupChecks {

    @ConfigProperty(name = "imports.github.token")
    String githubToken;

    @ConfigProperty(name = "imports.jira.token")
    String jiraToken;

    static Pattern EXPECTED_DECODED_JIRA_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9-_.]+@[a-zA-Z0-9-]+\\.[a-zA-Z]+:[a-zA-Z0-9=_-]+$");

    public void checkConfiguration(@Observes Startup startup) {
        if (githubToken == null || githubToken.isEmpty()) {
            throw new IllegalStateException("GitHub token is not configured. Please set the 'imports.github.token' configuration property.");
        }
        if (jiraToken == null || jiraToken.isEmpty()) {
            throw new IllegalStateException("Jira token is not configured. Please set the 'imports.jira.token' configuration property.");
        }
        // check that the token has the right format
        String decodedToken = new String(Base64.getDecoder().decode(jiraToken));
        if (!EXPECTED_DECODED_JIRA_TOKEN_PATTERN.matcher(decodedToken).matches()) {
            throw new IllegalStateException("The decoded Jira token does not match the expected format." +
                    " Please check the 'imports.jira.token' configuration property (or 'IMPORTS_JIRA_TOKEN' env variable)." +
                    " It should be encoded using: \"echo your.email@example.com:RAW-TOKEN | base64\"");
        }
    }
}
