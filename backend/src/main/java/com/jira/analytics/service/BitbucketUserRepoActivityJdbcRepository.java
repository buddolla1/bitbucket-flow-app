package com.jira.analytics.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class BitbucketUserRepoActivityJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public BitbucketUserRepoActivityJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void upsert(String bitbucketUsername, String projectKey, String repoSlug, OffsetDateTime lastPrSeenAt, OffsetDateTime lastScannedAt) {
        if (!StringUtils.hasText(bitbucketUsername) || !StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug)) {
            return;
        }
        Optional<Long> existing = findId(bitbucketUsername, projectKey, repoSlug);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE bitbucket_user_repo_activity
                    SET last_pr_seen_at = ?, last_scanned_at = ?
                    WHERE id = ?
                    """,
                    lastPrSeenAt,
                    lastScannedAt,
                    existing.get()
            );
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO bitbucket_user_repo_activity (
                    bitbucket_username, project_key, repo_slug, last_pr_seen_at, last_scanned_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                bitbucketUsername.trim(),
                projectKey.trim(),
                repoSlug.trim(),
                lastPrSeenAt,
                lastScannedAt
        );
    }

    @Transactional(readOnly = true)
    public Optional<Long> findId(String bitbucketUsername, String projectKey, String repoSlug) {
        if (!StringUtils.hasText(bitbucketUsername) || !StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT id
                FROM bitbucket_user_repo_activity
                WHERE bitbucket_username = ? AND project_key = ? AND repo_slug = ?
                """,
                (rs, rowNum) -> rs.getLong("id"),
                bitbucketUsername.trim(),
                projectKey.trim(),
                repoSlug.trim()
        ).stream().findFirst();
    }
}
