package com.jira.analytics.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JiraIssueLookupRepository {

    private final JdbcTemplate jdbcTemplate;

    public JiraIssueLookupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OffsetDateTime> findToDoToInProgressAt(String issueKey) {
        if (!StringUtils.hasText(issueKey)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT to_do_to_in_progress_at FROM jira_issue_record WHERE issue_key = ?",
                rs -> {
                    if (!rs.next()) {
                        return Optional.<OffsetDateTime>empty();
                    }
                    return Optional.ofNullable(rs.getObject(1, OffsetDateTime.class));
                },
                issueKey.trim()
        );
    }
}
