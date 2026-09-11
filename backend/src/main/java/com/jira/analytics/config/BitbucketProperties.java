package com.jira.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitbucket")
public class BitbucketProperties {

    private String baseUrl;
    private String username;
    private String apiToken;
    private String userLookupPath = "/rest/api/1.0/users?filter=";
    private String userPullRequestsPathTemplate = "/rest/awesome-graphs-api/latest/users/{user}/pull-requests";
    private int pageSize = 100;
    private int syncConcurrency = 5;
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

    public String getUserPullRequestsPathTemplate() {
        return userPullRequestsPathTemplate;
    }

    public void setUserPullRequestsPathTemplate(String userPullRequestsPathTemplate) {
        this.userPullRequestsPathTemplate = userPullRequestsPathTemplate;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getSyncConcurrency() {
        return syncConcurrency;
    }

    public void setSyncConcurrency(int syncConcurrency) {
        this.syncConcurrency = syncConcurrency;
    }

    public int getUserLookupTtlHours() {
        return userLookupTtlHours;
    }

    public void setUserLookupTtlHours(int userLookupTtlHours) {
        this.userLookupTtlHours = userLookupTtlHours;
    }
}
