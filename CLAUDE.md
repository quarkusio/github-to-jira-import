# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Quarkus web application that imports GitHub pull requests into Jira as issues. Built specifically for the Quarkus project's backport workflow: it reads PR candidates from GitHub backport projects (via GraphQL API) and creates corresponding Jira issues (via Atlassian JIRA REST Java Client).

## Build & Test Commands

```bash
# Build
./mvnw package

# Run in dev mode
./mvnw quarkus:dev

# Run tests (requires valid IMPORTS_GITHUB_TOKEN and IMPORTS_JIRA_TOKEN env vars)
./mvnw test

# Run a single test
./mvnw test -Dtest=SmokeTest#testGithubVersionToJiraFixVersion

# Native build
./mvnw package -Dnative
```

## Required Environment Variables

- `IMPORTS_GITHUB_TOKEN` - GitHub classic token with `project` permission (not fine-grained)
- `IMPORTS_JIRA_TOKEN` - Base64-encoded Jira PAT (`echo -n email@example.com:TOKEN | base64`)

Tests are integration tests that hit real GitHub and Jira APIs, so they require valid tokens.

## Architecture

The app has three main services:

- **`GithubToJiraResource`** - JAX-RS endpoint serving Qute-templated HTML pages. Handles the interactive workflow: index page (select branch/version) -> importing page (list PRs) -> perform import (create Jira issue). Caches PR data in-memory between page loads.
- **`GitHubService`** - Queries GitHub via SmallRye GraphQL dynamic client. Fetches backport projects from the `quarkusio` organization and retrieves PRs associated with a specific project/version using paginated GraphQL queries.
- **`JiraService`** - Manages Jira operations using `jira-rest-java-client` (Atlassian SDK). Creates issues with configured type (bug/component-upgrade/story), sets fix version, assigns, and optionally transitions to a target state.

Model classes in `io.quarkus.githubtojira.model`: `ProjectInfo`, `PullRequestInfo`, `JiraInfo` - plain Java beans (getters/setters).

## Key Configuration (application.properties)

- `jira.server` - Jira instance URL (default: `https://redhat.atlassian.net/`)
- `jira.project` - Jira project key (default: `QUARKUS`)
- `github.organization` / `github.repository` - GitHub org/repo (default: `quarkusio`/`quarkus`)
- `testing-run=true` - Prefixes created issues with `[TESTING, PLEASE IGNORE]`
- `jira.transition-to-state` - Jira transition ID applied after issue creation (0 to skip)

## Tech Stack

- Java 21, Quarkus 3.x
- Quarkus REST (JAX-RS), Qute templates, SmallRye GraphQL Client
- Atlassian JIRA REST Java Client 7.x (with Fugue)
- Fomantic UI (CSS framework in templates)
- AssertJ for test assertions
