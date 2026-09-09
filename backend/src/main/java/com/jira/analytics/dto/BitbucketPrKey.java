package com.jira.analytics.dto;

public record BitbucketPrKey(
        String projectKey,
        String repoSlug,
        Long prId
) {
}
