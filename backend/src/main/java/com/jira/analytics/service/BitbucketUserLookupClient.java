package com.jira.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.analytics.config.BitbucketProperties;
import com.jira.analytics.dto.BitbucketUserMapping;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BitbucketUserLookupClient {

    private final BitbucketProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BitbucketUserLookupClient(BitbucketProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Optional<BitbucketUserMapping> resolveBitbucketUser(String sso) {
        if (!StringUtils.hasText(sso)) {
            return Optional.empty();
        }

        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("Missing Bitbucket base URL. Set BITBUCKET_BASE_URL.");
        }

        String encodedSso = URLEncoder.encode(sso.trim(), StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + properties.getUserLookupPath() + encodedSso);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "application/json")
                .headers(optionalAuthorizationHeader())
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Bitbucket user lookup failed with HTTP " + response.statusCode());
            }
            return extractUserMapping(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to resolve Bitbucket user mapping.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to resolve Bitbucket user mapping.", exception);
        }
    }

    private Optional<BitbucketUserMapping> extractUserMapping(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        List<JsonNode> candidates = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(candidates::add);
        } else if (root.has("values") && root.get("values").isArray()) {
            root.get("values").forEach(candidates::add);
        } else {
            candidates.add(root);
        }

        for (JsonNode candidate : candidates) {
            String userId = firstText(candidate, "id", "userId", "bitbucketUserId", "name");
            String username = firstText(candidate, "name", "username", "slug");
            String slug = firstText(candidate, "slug", "name", "username");
            if (!StringUtils.hasText(userId) && !StringUtils.hasText(username) && !StringUtils.hasText(slug)) {
                continue;
            }
            return Optional.of(new BitbucketUserMapping(
                    normalize(userId),
                    normalize(username),
                    normalize(slug),
                    OffsetDateTime.now(ZoneOffset.UTC)
            ));
        }

        return Optional.empty();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode candidate = node.get(field);
            if (candidate != null && !candidate.isNull()) {
                String value = candidate.asText(null);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String[] optionalAuthorizationHeader() {
        String authorization = BitbucketAuthSupport.basicAuthHeader(properties);
        if (!StringUtils.hasText(authorization)) {
            return new String[0];
        }
        return new String[] {"Authorization", authorization};
    }
}
