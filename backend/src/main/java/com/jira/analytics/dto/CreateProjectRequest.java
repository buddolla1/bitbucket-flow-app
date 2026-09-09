package com.jira.analytics.dto;

public record CreateProjectRequest(
        String projectKey,
        String projectName
) {
}

