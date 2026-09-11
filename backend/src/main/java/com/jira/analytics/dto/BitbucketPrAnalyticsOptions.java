package com.jira.analytics.dto;

import java.util.List;

public record BitbucketPrAnalyticsOptions(
        List<String> repositories,
        List<String> authors,
        List<String> states
) {
}
