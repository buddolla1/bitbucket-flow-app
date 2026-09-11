package com.jira.analytics.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JiraIssueLookupRepository {

    private static final Logger log = LoggerFactory.getLogger(JiraIssueLookupRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public JiraIssueLookupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OffsetDateTime> findToDoToInProgressAt(String issueKey) {
        if (!StringUtils.hasText(issueKey)) {
            log.info("Skipping Jira transition lookup because issue key is blank");
            return Optional.empty();
        }
        Optional<OffsetDateTime> transitionAt = jdbcTemplate.query(
                "SELECT to_do_to_in_progress_at FROM jira_issue_record WHERE issue_key = ?",
                rs -> {
                    if (!rs.next()) {
                        return Optional.<OffsetDateTime>empty();
                    }
                    return Optional.ofNullable(rs.getObject(1, OffsetDateTime.class));
                },
                issueKey.trim()
        );
        log.info("Lookup Jira transition issueKey={} found={}", issueKey.trim(), transitionAt.isPresent());
        return transitionAt;
    }
}
