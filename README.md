# Quarkus Github to JIRA import

This application can be used to clone Quarkus issues from GitHub into product JIRA in a semi-automated interactive way.
It finds PR candidates using the backport projects (for example, [this one](https://github.com/orgs/quarkusio/projects/56) for the 3.20 branch.

*WARNING*: This script was written specifically for the Quarkus project and was not writen with other projects in mind. Therefore, there will be some parts of the code that only apply to the Quarkus process.

## Usage

Before running the application, make sure you define the following environment variables:
```
# needs the project:read permission of a user that has permission to read projects in the quarkusio org
GITHUB_TOKEN
# JIRA personal access token, 
JIRA_TOKEN
```

Run the app (using whichever method you prefer - `mvn quarkus:dev`, `mvn package && java -jar ...`, `quarkus run`,...).

Then, open the app (by default at http://localhost:8080) and follow the instructions.
On the initial page, you will be asked to select a branch (3.20, 3.15,...) and a target 
release (3.20.3) for which you want to import issues.

Then, the app will show you a list of PRs that are candidates for import. You can then select which ones to import.
For each PR, there are two buttons in the rightmost column - "Create as a bug" and "Create as a component upgrade".
It is up to you to decide which issue type is more appropriate.