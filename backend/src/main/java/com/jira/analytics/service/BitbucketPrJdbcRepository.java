package com.jira.analytics.service;

import com.jira.analytics.dto.BitbucketPrRecord;
import com.jira.analytics.dto.BitbucketPrAnalyticsQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class BitbucketPrJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(BitbucketPrJdbcRepository.class);

    private static final String SELECT_COLUMNS = """
            id, application_project_id, project_id, project_key, project_name, repository_name, repo_slug, pr_id,
            author_name, author_username, author_user_id, title, description, source_branch, destination_branch,
            state, jira_key, jira_mapping_source, pr_created_at, first_commit_at, first_review_engagement_at,
            pr_merged_at, cycle_start, cycle_start_source, cycle_time_days, last_synced_at
            """;
    private static final Map<String, String> SORT_COLUMNS = sortColumns();

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
        Optional<BitbucketPrRecord> record = rows.stream().findFirst();
        log.info("Lookup Bitbucket PR projectKey={} repoSlug={} prId={} found={}", projectKey.trim(), repoSlug.trim(), prId, record.isPresent());
        return record;
    }

    @Transactional(readOnly = true)
    public List<BitbucketPrRecord> findSyncedRecords(Long applicationProjectId) {
        if (applicationProjectId == null) {
            List<BitbucketPrRecord> records = jdbcTemplate.query(
                    "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr ORDER BY COALESCE(pr_merged_at, pr_created_at, last_synced_at) DESC, repo_slug, pr_id DESC",
                    (rs, rowNum) -> mapRow(rs)
            );
            log.info("Loaded {} synced Bitbucket PR records across all projects", records.size());
            return records;
        }
        List<BitbucketPrRecord> records = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr WHERE COALESCE(application_project_id, project_id) = ? ORDER BY COALESCE(pr_merged_at, pr_created_at, last_synced_at) DESC, repo_slug, pr_id DESC",
                (rs, rowNum) -> mapRow(rs),
                applicationProjectId
        );
        log.info("Loaded {} synced Bitbucket PR records for applicationProjectId={}", records.size(), applicationProjectId);
        return records;
    }

    @Transactional(readOnly = true)
    public List<BitbucketPrRecord> findAnalyticsRecords(BitbucketPrAnalyticsQuery query) {
        QueryParts parts = buildWhereClause(query);
        List<Object> args = new ArrayList<>(parts.args());
        args.add(Math.max(1, query.size()));
        args.add(Math.max(0, query.page()) * Math.max(1, query.size()));
        List<BitbucketPrRecord> records = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr " + parts.whereClause()
                        + " ORDER BY " + sortColumn(query.sort()) + " " + sortDirection(query.direction()) + ", repo_slug, pr_id DESC"
                        + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapRow(rs),
                args.toArray()
        );
        log.info("Loaded {} analytics PR records page={} size={}", records.size(), query.page(), query.size());
        return records;
    }

    @Transactional(readOnly = true)
    public List<BitbucketPrRecord> findAnalyticsSummaryRecords(BitbucketPrAnalyticsQuery query) {
        QueryParts parts = buildWhereClause(query);
        List<BitbucketPrRecord> records = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM bitbucket_pr " + parts.whereClause(),
                (rs, rowNum) -> mapRow(rs),
                parts.args().toArray()
        );
        log.info("Loaded {} analytics summary PR records", records.size());
        return records;
    }

    @Transactional(readOnly = true)
    public long countAnalyticsRecords(BitbucketPrAnalyticsQuery query) {
        QueryParts parts = buildWhereClause(query);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bitbucket_pr " + parts.whereClause(),
                Long.class,
                parts.args().toArray()
        );
        long total = count == null ? 0L : count;
        log.info("Counted {} analytics PR records", total);
        return total;
    }

    @Transactional
    void batchInsert(List<BitbucketPrRecord> records) {
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
        log.info("Inserted {} Bitbucket PR records", records.size());
    }

    @Transactional
    void batchUpdate(List<BitbucketPrRecord> records) {
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
        log.info("Updated {} Bitbucket PR records", records.size());
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
        log.info("Persisted Bitbucket PR records inserted={} updated={}", inserts.size(), updates.size());
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

    private QueryParts buildWhereClause(BitbucketPrAnalyticsQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (query != null && query.projectId() != null) {
            clauses.add("COALESCE(application_project_id, project_id) = ?");
            args.add(query.projectId());
        }
        if (query != null && StringUtils.hasText(query.repository())) {
            clauses.add("repo_slug = ?");
            args.add(query.repository().trim());
        }
        if (query != null && StringUtils.hasText(query.author())) {
            clauses.add("COALESCE(NULLIF(author_name, ''), NULLIF(author_username, ''), NULLIF(author_user_id, '')) = ?");
            args.add(query.author().trim());
        }
        if (query != null && StringUtils.hasText(query.state())) {
            clauses.add("UPPER(state) = UPPER(?)");
            args.add(query.state().trim());
        }
        if (query != null && StringUtils.hasText(query.jira())) {
            clauses.add("LOWER(COALESCE(jira_key, '')) LIKE ?");
            args.add("%" + query.jira().trim().toLowerCase() + "%");
        }
        if (query != null && query.createdFrom() != null) {
            clauses.add("pr_created_at >= ?");
            args.add(query.createdFrom().atStartOfDay());
        }
        if (query != null && query.createdTo() != null) {
            clauses.add("pr_created_at < ?");
            args.add(query.createdTo().plusDays(1).atStartOfDay());
        }
        if (query != null && query.mergedFrom() != null) {
            clauses.add("pr_merged_at >= ?");
            args.add(query.mergedFrom().atStartOfDay());
        }
        if (query != null && query.mergedTo() != null) {
            clauses.add("pr_merged_at < ?");
            args.add(query.mergedTo().plusDays(1).atStartOfDay());
        }
        return new QueryParts(clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses), args);
    }

    private String sortColumn(String sort) {
        if (!StringUtils.hasText(sort)) {
            return SORT_COLUMNS.get("activity");
        }
        return SORT_COLUMNS.getOrDefault(sort.trim(), SORT_COLUMNS.get("activity"));
    }

    private String sortDirection(String direction) {
        return "asc".equalsIgnoreCase(direction) ? "ASC" : "DESC";
    }

    private static Map<String, String> sortColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("activity", "COALESCE(pr_merged_at, pr_created_at, last_synced_at)");
        columns.put("created", "pr_created_at");
        columns.put("merged", "pr_merged_at");
        columns.put("cycle", "cycle_time_days");
        columns.put("repo", "repo_slug");
        columns.put("author", "COALESCE(NULLIF(author_name, ''), NULLIF(author_username, ''), NULLIF(author_user_id, ''))");
        columns.put("state", "state");
        columns.put("jira", "jira_key");
        return columns;
    }

    private record QueryParts(String whereClause, List<Object> args) {
    }
}
