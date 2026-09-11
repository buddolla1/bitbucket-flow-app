package com.jira.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.analytics.config.BitbucketProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BitbucketApiClient {

    private final BitbucketProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BitbucketApiClient(BitbucketProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public List<JsonNode> getUserPullRequests(String userName) {
        if (!StringUtils.hasText(userName)) {
            return List.of();
        }

        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("state", List.of("ALL"));
        params.put("limit", List.of(String.valueOf(properties.getPageSize())));

        return fetchPagedValues(resolveUserPullRequestsPath(userName.trim()), params);
    }

    public JsonNode getPullRequest(String projectKey, String repoSlug, long prId) {
        return fetchJson(resolveDetailPath(projectKey, repoSlug, prId), Map.of());
    }

    public List<JsonNode> getPullRequestCommits(String projectKey, String repoSlug, long prId) {
        return fetchPagedValues(resolveDetailPath(projectKey, repoSlug, prId) + "/commits", Map.of("limit", List.of(String.valueOf(properties.getPageSize()))));
    }

    public List<JsonNode> getPullRequestActivities(String projectKey, String repoSlug, long prId) {
        return fetchPagedValues(resolveDetailPath(projectKey, repoSlug, prId) + "/activities", Map.of("limit", List.of(String.valueOf(properties.getPageSize()))));
    }

    private List<JsonNode> fetchPagedValues(String path, Map<String, List<String>> baseParams) {
        List<JsonNode> values = new ArrayList<>();
        Integer start = null;
        boolean lastPage = false;

        while (!lastPage) {
            Map<String, List<String>> params = new LinkedHashMap<>(baseParams);
            params.putIfAbsent("limit", List.of(String.valueOf(properties.getPageSize())));
            if (start != null) {
                params.put("start", List.of(String.valueOf(start)));
            }

            JsonNode response = fetchJson(path, params);
            extractValues(response).forEach(values::add);

            if (!hasPaging(response)) {
                break;
            }
            lastPage = response.path("isLastPage").asBoolean(true);
            if (!lastPage) {
                int next = response.path("nextPageStart").asInt(-1);
                if (next < 0) {
                    break;
                }
                start = next;
            }
        }

        return values;
    }

    private JsonNode fetchJson(String path, Map<String, List<String>> queryParams) {
        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("Missing Bitbucket base URL. Set BITBUCKET_BASE_URL.");
        }

        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + path + toQueryString(queryParams));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "application/json");
        String authorization = BitbucketAuthSupport.basicAuthHeader(properties);
        if (StringUtils.hasText(authorization)) {
            requestBuilder.header("Authorization", authorization);
        }
        HttpRequest request = requestBuilder.build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Bitbucket request failed with HTTP " + response.statusCode() + " for " + uri);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call Bitbucket REST API.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call Bitbucket REST API.", exception);
        }
    }

    private List<JsonNode> extractValues(JsonNode response) {
        List<JsonNode> values = new ArrayList<>();
        if (response == null || response.isNull() || response.isMissingNode()) {
            return values;
        }
        if (response.isArray()) {
            response.forEach(values::add);
            return values;
        }
        for (String field : List.of("values", "pullRequests", "results", "items")) {
            JsonNode candidate = response.get(field);
            if (candidate != null && candidate.isArray()) {
                candidate.forEach(values::add);
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        values.add(response);
        return values;
    }

    private boolean hasPaging(JsonNode response) {
        return response != null && response.isObject() && (response.has("isLastPage") || response.has("nextPageStart") || response.has("start"));
    }

    private String resolveDetailPath(String projectKey, String repoSlug, long prId) {
        return "/rest/api/1.0/projects/" + encode(projectKey) + "/repos/" + encode(repoSlug) + "/pull-requests/" + prId;
    }

    private String resolveUserPullRequestsPath(String userName) {
        String template = properties.getUserPullRequestsPathTemplate();
        if (!StringUtils.hasText(template)) {
            template = "/rest/awesome-graphs-api/latest/users/{user}/pull-requests";
        }
        String encodedUser = encode(userName);
        if (template.contains("{user}")) {
            return template.replace("{user}", encodedUser);
        }
        if (template.endsWith("/")) {
            return template + encodedUser + "/pull-requests";
        }
        return template + "/" + encodedUser + "/pull-requests";
    }

    private String toQueryString(Map<String, List<String>> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(encode(entry.getKey())).append('=').append(encode(value));
            }
        }
        return builder.length() == 1 ? "" : builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
