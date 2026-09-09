package com.jira.analytics.service;

import com.jira.analytics.dto.BitbucketUserMapping;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class SsoUserIdJdbcRepository {

    private static final String SELECT_COLUMNS = "id, project_id, sso, bitbucket_user_id, bitbucket_username, bitbucket_slug, last_resolved_at";

    private final JdbcTemplate jdbcTemplate;

    public SsoUserIdJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Optional<SsoUserIdMapping> findByProjectIdAndSso(Long projectId, String sso) {
        if (projectId == null || !StringUtils.hasText(sso)) {
            return Optional.empty();
        }
        List<SsoUserIdMapping> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM sso_userid WHERE project_id = ? AND sso = ?",
                (rs, rowNum) -> new SsoUserIdMapping(
                        rs.getLong("id"),
                        rs.getLong("project_id"),
                        rs.getString("sso"),
                        rs.getString("bitbucket_user_id"),
                        rs.getString("bitbucket_username"),
                        rs.getString("bitbucket_slug"),
                        rs.getObject("last_resolved_at", OffsetDateTime.class)
                ),
                projectId,
                sso.trim()
        );
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<SsoUserIdMapping> findByProjectId(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM sso_userid WHERE project_id = ? ORDER BY sso",
                (rs, rowNum) -> new SsoUserIdMapping(
                        rs.getLong("id"),
                        rs.getLong("project_id"),
                        rs.getString("sso"),
                        rs.getString("bitbucket_user_id"),
                        rs.getString("bitbucket_username"),
                        rs.getString("bitbucket_slug"),
                        rs.getObject("last_resolved_at", OffsetDateTime.class)
                ),
                projectId
        );
    }

    @Transactional
    public void batchInsert(List<SsoUserIdMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO sso_userid (
                    project_id, sso, bitbucket_user_id, bitbucket_username, bitbucket_slug, last_resolved_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                mappings,
                500,
                (ps, mapping) -> {
                    ps.setLong(1, mapping.projectId());
                    ps.setString(2, mapping.sso());
                    ps.setString(3, mapping.bitbucketUserId());
                    ps.setString(4, mapping.bitbucketUsername());
                    ps.setString(5, mapping.bitbucketSlug());
                    ps.setObject(6, mapping.lastResolvedAt());
                }
        );
    }

    @Transactional
    public void batchUpdate(List<SsoUserIdMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                UPDATE sso_userid
                SET bitbucket_user_id = ?, bitbucket_username = ?, bitbucket_slug = ?, last_resolved_at = ?
                WHERE id = ?
                """,
                mappings,
                500,
                (ps, mapping) -> {
                    ps.setString(1, mapping.bitbucketUserId());
                    ps.setString(2, mapping.bitbucketUsername());
                    ps.setString(3, mapping.bitbucketSlug());
                    ps.setObject(4, mapping.lastResolvedAt());
                    ps.setLong(5, mapping.id());
                }
        );
    }

    @Transactional
    public SsoUserIdMapping saveOrUpdate(Long projectId, String sso, BitbucketUserMapping mapping) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project id is required.");
        }
        if (!StringUtils.hasText(sso)) {
            throw new IllegalArgumentException("SSO is required.");
        }
        if (mapping == null || !mapping.isValid()) {
            throw new IllegalArgumentException("Bitbucket user mapping is required.");
        }

        OffsetDateTime resolvedAt = mapping.lastResolvedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : mapping.lastResolvedAt();
        Optional<SsoUserIdMapping> existing = findByProjectIdAndSso(projectId, sso);
        if (existing.isPresent()) {
            SsoUserIdMapping current = existing.get();
            if (!safeEquals(mapping.bitbucketUserId(), current.bitbucketUserId())
                    || !safeEquals(mapping.bitbucketUsername(), current.bitbucketUsername())
                    || !safeEquals(mapping.bitbucketSlug(), current.bitbucketSlug())) {
                jdbcTemplate.update(
                        """
                        UPDATE sso_userid
                        SET bitbucket_user_id = ?, bitbucket_username = ?, bitbucket_slug = ?, last_resolved_at = ?
                        WHERE id = ?
                        """,
                        trimOrNull(mapping.bitbucketUserId()),
                        trimOrNull(mapping.bitbucketUsername()),
                        trimOrNull(mapping.bitbucketSlug()),
                        resolvedAt,
                        current.id()
                );
                return new SsoUserIdMapping(
                        current.id(),
                        projectId,
                        sso.trim(),
                        trimOrNull(mapping.bitbucketUserId()),
                        trimOrNull(mapping.bitbucketUsername()),
                        trimOrNull(mapping.bitbucketSlug()),
                        resolvedAt
                );
            }

            jdbcTemplate.update(
                    "UPDATE sso_userid SET last_resolved_at = ? WHERE id = ?",
                    resolvedAt,
                    current.id()
            );
            return new SsoUserIdMapping(
                    current.id(),
                    projectId,
                    sso.trim(),
                    current.bitbucketUserId(),
                    current.bitbucketUsername(),
                    current.bitbucketSlug(),
                    resolvedAt
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO sso_userid (
                    project_id, sso, bitbucket_user_id, bitbucket_username, bitbucket_slug, last_resolved_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                projectId,
                sso.trim(),
                trimOrNull(mapping.bitbucketUserId()),
                trimOrNull(mapping.bitbucketUsername()),
                trimOrNull(mapping.bitbucketSlug()),
                resolvedAt
        );
        return findByProjectIdAndSso(projectId, sso).orElseThrow();
    }

    public record SsoUserIdMapping(
            Long id,
            Long projectId,
            String sso,
            String bitbucketUserId,
            String bitbucketUsername,
            String bitbucketSlug,
            OffsetDateTime lastResolvedAt
    ) {
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = trimOrNull(left);
        String normalizedRight = trimOrNull(right);
        if (normalizedLeft == null) {
            return normalizedRight == null;
        }
        return normalizedLeft.equals(normalizedRight);
    }
}
