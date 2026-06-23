package io.quarkus.githubtojira.model;

import java.util.List;

public class PullRequestInfo {

    private String url;
    private String title;
    private Integer number;
    private List<JiraInfo> existingJiras;
    private String description;
    private List<String> labels;
    private List<String> files;
    private Iterable<String> jiraLabels;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public List<JiraInfo> getExistingJiras() {
        return existingJiras;
    }

    public void setExistingJiras(List<JiraInfo> existingJiras) {
        this.existingJiras = existingJiras;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }

    public Iterable<String> getJiraLabels() {
        return jiraLabels;
    }

    public void setJiraLabels(Iterable<String> jiraLabels) {
        this.jiraLabels = jiraLabels;
    }

    @Override
    public String toString() {
        return "PullRequestInfo{" +
                "url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", number=" + number +
                ", existingJiras=" + existingJiras +
                ", labels=" + labels +
                ", files=" + files +
                ", jiraLabels=" + jiraLabels +
                '}';
    }
}
