package com.jira.analytics.service;

import com.jira.analytics.config.BitbucketProperties;
import com.jira.analytics.dto.BitbucketCatalogRefreshResult;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BitbucketCatalogService {

    private final BitbucketApiClient bitbucketApiClient;
    private final BitbucketCatalogJdbcRepository catalogRepository;
    private final BitbucketProperties properties;

    public BitbucketCatalogService(
            BitbucketApiClient bitbucketApiClient,
            BitbucketCatalogJdbcRepository catalogRepository,
            BitbucketProperties properties
    ) {
        this.bitbucketApiClient = bitbucketApiClient;
        this.catalogRepository = catalogRepository;
        this.properties = properties;
    }

    public BitbucketCatalogRefreshResult refreshCatalog() {
        OffsetDateTime refreshedAt = OffsetDateTime.now(ZoneOffset.UTC);
        int projectsDiscovered = 0;
        int repositoriesDiscovered = 0;

        List<com.fasterxml.jackson.databind.JsonNode> projects = bitbucketApiClient.discoverProjects();
        for (com.fasterxml.jackson.databind.JsonNode project : projects) {
            String projectKey = firstText(project, "key", "projectKey", "id");
            if (!StringUtils.hasText(projectKey)) {
                continue;
            }
            String projectName = firstText(project, "name", "projectName");
            catalogRepository.upsertProject(projectKey, projectName, refreshedAt);
            projectsDiscovered++;

            List<com.fasterxml.jackson.databind.JsonNode> repositories = bitbucketApiClient.discoverRepositories(projectKey);
            for (com.fasterxml.jackson.databind.JsonNode repository : repositories) {
                String repoSlug = firstText(repository, "slug", "repoSlug");
                if (!StringUtils.hasText(repoSlug)) {
                    continue;
                }
                String repoName = firstText(repository, "name", "repositoryName");
                catalogRepository.upsertRepository(projectKey, repoSlug, repoName, refreshedAt);
                repositoriesDiscovered++;
            }
        }

        return new BitbucketCatalogRefreshResult("REFRESHED", projectsDiscovered, repositoriesDiscovered, refreshedAt);
    }

    public BitbucketCatalogRefreshResult ensureFreshCatalog(boolean forceRefresh) {
        if (forceRefresh || catalogRepository.isEmpty() || isCatalogStale()) {
            return refreshCatalog();
        }
        OffsetDateTime latestDiscovery = catalogRepository.findLatestDiscoveryAt().orElse(OffsetDateTime.now(ZoneOffset.UTC));
        return new BitbucketCatalogRefreshResult("REUSED", catalogRepository.countProjects(), catalogRepository.countRepositories(), latestDiscovery);
    }

    public List<BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow> findActiveRepositories() {
        return catalogRepository.findActiveRepositories();
    }

    private boolean isCatalogStale() {
        return catalogRepository.findLatestDiscoveryAt()
                .map(last -> Duration.between(last, OffsetDateTime.now(ZoneOffset.UTC)).toHours() >= properties.getCatalogTtlHours())
                .orElse(true);
    }

    private String firstText(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            com.fasterxml.jackson.databind.JsonNode candidate = node.get(field);
            if (candidate != null && !candidate.isNull()) {
                String value = candidate.asText(null);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }
}
