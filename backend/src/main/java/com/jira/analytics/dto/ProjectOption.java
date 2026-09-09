package com.jira.analytics.dto;

public record ProjectOption(
        Long projectId,
        String projectKey,
        String projectName
) {
}
