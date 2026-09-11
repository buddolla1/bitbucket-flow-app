package com.jira.analytics.dto;

import java.util.List;

public record BitbucketPrAnalyticsPage(
        List<BitbucketPrRecord> records,
        long totalRecords,
        int page,
        int size,
        int totalPages,
        BitbucketPrAnalyticsSummary summary,
        BitbucketPrAnalyticsOptions options
) {
}
