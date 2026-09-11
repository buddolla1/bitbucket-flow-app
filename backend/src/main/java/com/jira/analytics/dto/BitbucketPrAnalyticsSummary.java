package com.jira.analytics.dto;

public record BitbucketPrAnalyticsSummary(
        long total,
        long merged,
        long open,
        long staleOpen,
        Double averageCycleDays,
        Double medianCycleDays,
        Double p90CycleDays
) {
}
