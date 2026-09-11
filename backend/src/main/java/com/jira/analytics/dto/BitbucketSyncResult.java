package com.jira.analytics.dto;

import java.util.List;

public record BitbucketSyncResult(
        String status,
        Long projectId,
        int ssosRequested,
        int userIdsResolved,
        int repositoriesScanned,
        int prsDiscovered,
        int prsInserted,
        int prsUpdated,
        List<SyncError> errors,
        String syncTime
) {
}
