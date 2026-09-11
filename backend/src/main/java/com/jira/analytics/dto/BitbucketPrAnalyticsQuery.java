package com.jira.analytics.dto;

import java.time.LocalDate;

public record BitbucketPrAnalyticsQuery(
        Long projectId,
        String repository,
        String author,
        String state,
        String jira,
        LocalDate createdFrom,
        LocalDate createdTo,
        LocalDate mergedFrom,
        LocalDate mergedTo,
        int page,
        int size,
        String sort,
        String direction
) {
}
