package com.jira.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitbucket")
public class BitbucketProperties {

    private String baseUrl;
    private String username;
    private String apiToken;
    private String userLookupPath = "/rest/api/1.0/users?filter=";
    private int pageSize = 100;
    private int catalogTtlHours = 24;
    private int syncConcurrency = 5;
    private int fullScanDays = 7;
    private int participantBatchSize = 25;
    private int userLookupTtlHours = 168;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getUserLookupPath() {
        return userLookupPath;
    }

    public void setUserLookupPath(String userLookupPath) {
        this.userLookupPath = userLookupPath;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getCatalogTtlHours() {
        return catalogTtlHours;
    }

    public void setCatalogTtlHours(int catalogTtlHours) {
        this.catalogTtlHours = catalogTtlHours;
    }

    public int getSyncConcurrency() {
        return syncConcurrency;
    }

    public void setSyncConcurrency(int syncConcurrency) {
        this.syncConcurrency = syncConcurrency;
    }

    public int getFullScanDays() {
        return fullScanDays;
    }

    public void setFullScanDays(int fullScanDays) {
        this.fullScanDays = fullScanDays;
    }

    public int getParticipantBatchSize() {
        return participantBatchSize;
    }

    public void setParticipantBatchSize(int participantBatchSize) {
        this.participantBatchSize = participantBatchSize;
    }

    public int getUserLookupTtlHours() {
        return userLookupTtlHours;
    }

    public void setUserLookupTtlHours(int userLookupTtlHours) {
        this.userLookupTtlHours = userLookupTtlHours;
    }
}
