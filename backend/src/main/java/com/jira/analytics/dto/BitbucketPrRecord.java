package com.jira.analytics.dto;

import java.time.OffsetDateTime;

public record BitbucketPrRecord(
        Long id,
        Long applicationProjectId,
        String projectKey,
        String projectName,
        String repositoryName,
        String repoSlug,
        Long prId,
        String authorName,
        String authorUsername,
        String authorUserId,
        String title,
        String description,
        String sourceBranch,
        String destinationBranch,
        String state,
        String jiraKey,
        String jiraMappingSource,
        OffsetDateTime prCreatedAt,
        OffsetDateTime firstCommitAt,
        OffsetDateTime firstReviewEngagementAt,
        OffsetDateTime prMergedAt,
        OffsetDateTime cycleStart,
        String cycleStartSource,
        Double cycleTimeDays,
        OffsetDateTime lastSyncedAt
) {
}
