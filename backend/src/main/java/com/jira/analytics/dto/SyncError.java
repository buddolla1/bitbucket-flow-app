package com.jira.analytics.dto;

public record SyncError(
        String sso,
        String error
) {
}
