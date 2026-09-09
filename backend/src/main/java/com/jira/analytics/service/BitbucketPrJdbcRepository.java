package com.jira.analytics.service;

import com.jira.analytics.dto.BitbucketPrKey;
import com.jira.analytics.dto.BitbucketPrRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class BitbucketPrJdbcRepository {

    private static final String SELECT_COLUMNS = """
            id, application_project_id, project_id, project_key, project_name, repository_name, repo_slug, pr_id,
            author_name, author_username, author_user_id, title, description, source_branch, destination_branch,
            state, jira_key, jira_mapping_source, pr_created_at, first_commit_at, first_review_engagement_at,
            pr_merged_at, cycle_start, cycle_start_source, cycle_time_days, last_synced_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public BitbucketPrJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Optional<BitbucketPrRecord> findByNaturalKey(String projectKey, String repoSlug, Long prId) {
        if (!StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug) || prId == null) {
            return Optional.empty();
        }
        List<BitbucketPrRecord> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr WHERE project_key = ? AND repo_slug = ? AND pr_id = ?",
                (rs, rowNum) -> mapRow(rs),
                projectKey.trim(),
                repoSlug.trim(),
                prId
        );
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Set<BitbucketPrKey> findAllExistingKeys(Long applicationProjectId) {
        if (applicationProjectId == null) {
            return Set.of();
        }
        List<BitbucketPrKey> rows = jdbcTemplate.query(
                """
                SELECT project_key, repo_slug, pr_id
                FROM bitbucket_pr
                WHERE COALESCE(application_project_id, project_id) = ?
                """,
                (rs, rowNum) -> new BitbucketPrKey(
                        rs.getString("project_key"),
                        rs.getString("repo_slug"),
                        rs.getLong("pr_id")
                ),
                applicationProjectId
        );
        return new HashSet<>(rows);
    }

    @Transactional(readOnly = true)
    public List<BitbucketPrRecord> findByProjectId(Long applicationProjectId) {
        if (applicationProjectId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr WHERE COALESCE(application_project_id, project_id) = ? ORDER BY repo_slug, pr_id",
                (rs, rowNum) -> mapRow(rs),
                applicationProjectId
        );
    }

    @Transactional(readOnly = true)
    public long countByProjectId(Long applicationProjectId) {
        if (applicationProjectId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bitbucket_pr WHERE COALESCE(application_project_id, project_id) = ?",
                Long.class,
                applicationProjectId
        );
        return count == null ? 0L : count;
    }

    @Transactional
    public void batchInsert(List<BitbucketPrRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO bitbucket_pr (
                    application_project_id, project_id, project_key, project_name, repository_name, repo_slug, pr_id,
                    author_name, author_username, author_user_id, title, description, source_branch, destination_branch,
                    state, jira_key, jira_mapping_source, pr_created_at, first_commit_at, first_review_engagement_at,
                    pr_merged_at, cycle_start, cycle_start_source, cycle_time_days, last_synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                records,
                500,
                (ps, record) -> bindInsert(ps, record)
        );
    }

    @Transactional
    public void batchUpdate(List<BitbucketPrRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                UPDATE bitbucket_pr
                SET application_project_id = ?,
                    project_id = ?,
                    project_key = ?,
                    project_name = ?,
                    repository_name = ?,
                    author_name = ?,
                    author_username = ?,
                    author_user_id = ?,
                    title = ?,
                    description = ?,
                    source_branch = ?,
                    destination_branch = ?,
                    state = ?,
                    jira_key = ?,
                    jira_mapping_source = ?,
                    pr_created_at = ?,
                    first_commit_at = ?,
                    first_review_engagement_at = ?,
                    pr_merged_at = ?,
                    cycle_start = ?,
                    cycle_start_source = ?,
                    cycle_time_days = ?,
                    last_synced_at = ?
                WHERE id = ?
                """,
                records,
                500,
                (ps, record) -> {
                    ps.setObject(1, record.applicationProjectId());
                    ps.setObject(2, record.applicationProjectId());
                    ps.setString(3, record.projectKey());
                    ps.setString(4, record.projectName());
                    ps.setString(5, record.repositoryName());
                    ps.setString(6, record.authorName());
                    ps.setString(7, record.authorUsername());
                    ps.setString(8, record.authorUserId());
                    ps.setString(9, record.title());
                    ps.setString(10, record.description());
                    ps.setString(11, record.sourceBranch());
                    ps.setString(12, record.destinationBranch());
                    ps.setString(13, record.state());
                    ps.setString(14, record.jiraKey());
                    ps.setString(15, record.jiraMappingSource());
                    ps.setObject(16, record.prCreatedAt());
                    ps.setObject(17, record.firstCommitAt());
                    ps.setObject(18, record.firstReviewEngagementAt());
                    ps.setObject(19, record.prMergedAt());
                    ps.setObject(20, record.cycleStart());
                    ps.setString(21, record.cycleStartSource());
                    ps.setObject(22, record.cycleTimeDays());
                    ps.setObject(23, record.lastSyncedAt());
                    ps.setLong(24, record.id());
                }
        );
    }

    @Transactional
    public PersistedCounts saveOrUpdateAll(List<BitbucketPrRecord> records) {
        if (records == null || records.isEmpty()) {
            return new PersistedCounts(0, 0);
        }

        List<BitbucketPrRecord> inserts = new ArrayList<>();
        List<BitbucketPrRecord> updates = new ArrayList<>();
        for (BitbucketPrRecord record : records) {
            Optional<BitbucketPrRecord> existing = findByNaturalKey(record.projectKey(), record.repoSlug(), record.prId());
            if (existing.isPresent()) {
                updates.add(new BitbucketPrRecord(
                        existing.get().id(),
                        record.applicationProjectId(),
                        record.projectKey(),
                        record.projectName(),
                        record.repositoryName(),
                        record.repoSlug(),
                        record.prId(),
                        record.authorName(),
                        record.authorUsername(),
                        record.authorUserId(),
                        record.title(),
                        record.description(),
                        record.sourceBranch(),
                        record.destinationBranch(),
                        record.state(),
                        record.jiraKey(),
                        record.jiraMappingSource(),
                        record.prCreatedAt(),
                        record.firstCommitAt(),
                        record.firstReviewEngagementAt(),
                        record.prMergedAt(),
                        record.cycleStart(),
                        record.cycleStartSource(),
                        record.cycleTimeDays(),
                        record.lastSyncedAt()
                ));
            } else {
                inserts.add(record);
            }
        }

        batchInsert(inserts);
        batchUpdate(updates);
        return new PersistedCounts(inserts.size(), updates.size());
    }

    private void bindInsert(java.sql.PreparedStatement ps, BitbucketPrRecord record) throws java.sql.SQLException {
        ps.setObject(1, record.applicationProjectId());
        ps.setObject(2, record.applicationProjectId());
        ps.setString(3, record.projectKey());
        ps.setString(4, record.projectName());
        ps.setString(5, record.repositoryName());
        ps.setString(6, record.repoSlug());
        ps.setLong(7, record.prId());
        ps.setString(8, record.authorName());
        ps.setString(9, record.authorUsername());
        ps.setString(10, record.authorUserId());
        ps.setString(11, record.title());
        ps.setString(12, record.description());
        ps.setString(13, record.sourceBranch());
        ps.setString(14, record.destinationBranch());
        ps.setString(15, record.state());
        ps.setString(16, record.jiraKey());
        ps.setString(17, record.jiraMappingSource());
        ps.setObject(18, record.prCreatedAt());
        ps.setObject(19, record.firstCommitAt());
        ps.setObject(20, record.firstReviewEngagementAt());
        ps.setObject(21, record.prMergedAt());
        ps.setObject(22, record.cycleStart());
        ps.setString(23, record.cycleStartSource());
        ps.setObject(24, record.cycleTimeDays());
        ps.setObject(25, record.lastSyncedAt());
    }

    private BitbucketPrRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long applicationProjectId = rs.getObject("application_project_id", Long.class);
        if (applicationProjectId == null) {
            applicationProjectId = rs.getObject("project_id", Long.class);
        }
        return new BitbucketPrRecord(
                rs.getLong("id"),
                applicationProjectId,
                rs.getString("project_key"),
                rs.getString("project_name"),
                rs.getString("repository_name"),
                rs.getString("repo_slug"),
                rs.getLong("pr_id"),
                rs.getString("author_name"),
                rs.getString("author_username"),
                rs.getString("author_user_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("source_branch"),
                rs.getString("destination_branch"),
                rs.getString("state"),
                rs.getString("jira_key"),
                rs.getString("jira_mapping_source"),
                rs.getObject("pr_created_at", java.time.OffsetDateTime.class),
                rs.getObject("first_commit_at", java.time.OffsetDateTime.class),
                rs.getObject("first_review_engagement_at", java.time.OffsetDateTime.class),
                rs.getObject("pr_merged_at", java.time.OffsetDateTime.class),
                rs.getObject("cycle_start", java.time.OffsetDateTime.class),
                rs.getString("cycle_start_source"),
                rs.getObject("cycle_time_days", Double.class),
                rs.getObject("last_synced_at", java.time.OffsetDateTime.class)
        );
    }

    public record PersistedCounts(
            int inserted,
            int updated
    ) {
    }
}
