package com.jira.analytics.dto;

import java.time.OffsetDateTime;

public record BitbucketCatalogRefreshResult(
        String status,
        int projectsDiscovered,
        int repositoriesDiscovered,
        OffsetDateTime refreshedAt
) {
}
