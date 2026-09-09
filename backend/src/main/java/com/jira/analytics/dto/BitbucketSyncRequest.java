package com.jira.analytics.dto;

import java.util.List;

public record BitbucketSyncRequest(
        Long projectId,
        String fromDate,
        String toDate,
        List<String> ssos,
        Boolean fullRefresh
) {
}
