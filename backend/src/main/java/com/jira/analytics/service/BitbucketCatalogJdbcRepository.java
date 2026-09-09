package com.jira.analytics.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class BitbucketCatalogJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public BitbucketCatalogJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        Long projectCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bitbucket_project", Long.class);
        Long repositoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bitbucket_repository", Long.class);
        return (projectCount == null || projectCount == 0L) && (repositoryCount == null || repositoryCount == 0L);
    }

    @Transactional(readOnly = true)
    public int countProjects() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bitbucket_project", Long.class);
        return count == null ? 0 : Math.toIntExact(count);
    }

    @Transactional(readOnly = true)
    public int countRepositories() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bitbucket_repository", Long.class);
        return count == null ? 0 : Math.toIntExact(count);
    }

    @Transactional(readOnly = true)
    public Optional<OffsetDateTime> findLatestDiscoveryAt() {
        OffsetDateTime latestProject = jdbcTemplate.queryForObject(
                "SELECT MAX(last_discovered_at) FROM bitbucket_project",
                OffsetDateTime.class
        );
        OffsetDateTime latestRepository = jdbcTemplate.queryForObject(
                "SELECT MAX(last_discovered_at) FROM bitbucket_repository",
                OffsetDateTime.class
        );
        if (latestProject == null) {
            return Optional.ofNullable(latestRepository);
        }
        if (latestRepository == null) {
            return Optional.of(latestProject);
        }
        return Optional.of(latestProject.isAfter(latestRepository) ? latestProject : latestRepository);
    }

    @Transactional(readOnly = true)
    public List<BitbucketProjectCatalogRow> findProjects() {
        return jdbcTemplate.query(
                "SELECT project_key, project_name, active, last_discovered_at FROM bitbucket_project ORDER BY project_key",
                (rs, rowNum) -> new BitbucketProjectCatalogRow(
                        rs.getString("project_key"),
                        rs.getString("project_name"),
                        rs.getBoolean("active"),
                        rs.getObject("last_discovered_at", OffsetDateTime.class)
                )
        );
    }

    @Transactional(readOnly = true)
    public List<BitbucketRepositoryCatalogRow> findRepositories() {
        return jdbcTemplate.query(
                "SELECT project_key, repo_slug, repo_name, active, last_discovered_at FROM bitbucket_repository ORDER BY project_key, repo_slug",
                (rs, rowNum) -> new BitbucketRepositoryCatalogRow(
                        rs.getString("project_key"),
                        rs.getString("repo_slug"),
                        rs.getString("repo_name"),
                        rs.getBoolean("active"),
                        rs.getObject("last_discovered_at", OffsetDateTime.class)
                )
        );
    }

    @Transactional(readOnly = true)
    public List<BitbucketRepositoryCatalogRow> findActiveRepositories() {
        return jdbcTemplate.query(
                """
                SELECT project_key, repo_slug, repo_name, active, last_discovered_at
                FROM bitbucket_repository
                WHERE active = TRUE
                ORDER BY project_key, repo_slug
                """,
                (rs, rowNum) -> new BitbucketRepositoryCatalogRow(
                        rs.getString("project_key"),
                        rs.getString("repo_slug"),
                        rs.getString("repo_name"),
                        rs.getBoolean("active"),
                        rs.getObject("last_discovered_at", OffsetDateTime.class)
                )
        );
    }

    @Transactional
    public void upsertProject(String projectKey, String projectName, OffsetDateTime discoveredAt) {
        if (!StringUtils.hasText(projectKey)) {
            return;
        }
        String normalizedProjectKey = projectKey.trim();
        String normalizedProjectName = StringUtils.hasText(projectName) ? projectName.trim() : normalizedProjectKey;
        Optional<BitbucketProjectCatalogRow> existing = findProjectByKey(normalizedProjectKey);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE bitbucket_project
                    SET project_name = ?, active = TRUE, last_discovered_at = ?
                    WHERE project_key = ?
                    """,
                    normalizedProjectName,
                    discoveredAt,
                    normalizedProjectKey
            );
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO bitbucket_project (project_key, project_name, active, last_discovered_at)
                VALUES (?, ?, TRUE, ?)
                """,
                normalizedProjectKey,
                normalizedProjectName,
                discoveredAt
        );
    }

    @Transactional
    public void upsertRepository(String projectKey, String repoSlug, String repoName, OffsetDateTime discoveredAt) {
        if (!StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug)) {
            return;
        }
        String normalizedProjectKey = projectKey.trim();
        String normalizedRepoSlug = repoSlug.trim();
        String normalizedRepoName = StringUtils.hasText(repoName) ? repoName.trim() : normalizedRepoSlug;
        Optional<BitbucketRepositoryCatalogRow> existing = findRepositoryByKey(normalizedProjectKey, normalizedRepoSlug);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE bitbucket_repository
                    SET repo_name = ?, active = TRUE, last_discovered_at = ?
                    WHERE project_key = ? AND repo_slug = ?
                    """,
                    normalizedRepoName,
                    discoveredAt,
                    normalizedProjectKey,
                    normalizedRepoSlug
            );
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO bitbucket_repository (project_key, repo_slug, repo_name, active, last_discovered_at)
                VALUES (?, ?, ?, TRUE, ?)
                """,
                normalizedProjectKey,
                normalizedRepoSlug,
                normalizedRepoName,
                discoveredAt
        );
    }

    @Transactional(readOnly = true)
    public Optional<BitbucketProjectCatalogRow> findProjectByKey(String projectKey) {
        if (!StringUtils.hasText(projectKey)) {
            return Optional.empty();
        }
        List<BitbucketProjectCatalogRow> rows = jdbcTemplate.query(
                "SELECT project_key, project_name, active, last_discovered_at FROM bitbucket_project WHERE project_key = ?",
                (rs, rowNum) -> new BitbucketProjectCatalogRow(
                        rs.getString("project_key"),
                        rs.getString("project_name"),
                        rs.getBoolean("active"),
                        rs.getObject("last_discovered_at", OffsetDateTime.class)
                ),
                projectKey.trim()
        );
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<BitbucketRepositoryCatalogRow> findRepositoryByKey(String projectKey, String repoSlug) {
        if (!StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug)) {
            return Optional.empty();
        }
        List<BitbucketRepositoryCatalogRow> rows = jdbcTemplate.query(
                """
                SELECT project_key, repo_slug, repo_name, active, last_discovered_at
                FROM bitbucket_repository
                WHERE project_key = ? AND repo_slug = ?
                """,
                (rs, rowNum) -> new BitbucketRepositoryCatalogRow(
                        rs.getString("project_key"),
                        rs.getString("repo_slug"),
                        rs.getString("repo_name"),
                        rs.getBoolean("active"),
                        rs.getObject("last_discovered_at", OffsetDateTime.class)
                ),
                projectKey.trim(),
                repoSlug.trim()
        );
        return rows.stream().findFirst();
    }

    public record BitbucketProjectCatalogRow(
            String projectKey,
            String projectName,
            boolean active,
            OffsetDateTime lastDiscoveredAt
    ) {
    }

    public record BitbucketRepositoryCatalogRow(
            String projectKey,
            String repoSlug,
            String repoName,
            boolean active,
            OffsetDateTime lastDiscoveredAt
    ) {
    }
}
