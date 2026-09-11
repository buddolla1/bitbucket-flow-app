package com.jira.analytics.service;

import com.jira.analytics.dto.ProjectOption;
import com.jira.analytics.dto.ProjectSso;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class ProjectJdbcRepository {

    private static final String SELECT_PROJECT_COLUMNS = "id, project_key, project_name";

    private final JdbcTemplate jdbcTemplate;

    public ProjectJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public List<ProjectOption> findAll() {
        ensureProjectsFromJiraIssues();
        return jdbcTemplate.query(
                "SELECT " + SELECT_PROJECT_COLUMNS + " FROM project ORDER BY project_name, project_key",
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("project_key"),
                        rs.getString("project_name")
                )
        );
    }

    @Transactional(readOnly = true)
    public Optional<ProjectOption> findById(Long projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        List<ProjectOption> rows = jdbcTemplate.query(
                "SELECT " + SELECT_PROJECT_COLUMNS + " FROM project WHERE id = ?",
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("project_key"),
                        rs.getString("project_name")
                ),
                projectId
        );
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<ProjectSso> findSsosByProjectId(Long projectId) {
        Optional<ProjectOption> project = findById(projectId);
        if (project.isEmpty()) {
            return List.of();
        }

        String projectKey = project.get().projectKey();
        return jdbcTemplate.query(
                """
                SELECT
                    sso,
                    COALESCE(NULLIF(TRIM(MIN(display_name)), ''), sso) AS display_name
                FROM (
                    SELECT
                        COALESCE(NULLIF(TRIM(sso), ''), '') AS sso,
                        COALESCE(NULLIF(TRIM(assignee), ''), NULLIF(TRIM(sso), '')) AS display_name
                    FROM jira_issue_record
                    WHERE project_key = ?
                      AND sso IS NOT NULL
                      AND TRIM(sso) <> ''
                    UNION
                    SELECT
                        COALESCE(NULLIF(TRIM(sso), ''), '') AS sso,
                        COALESCE(NULLIF(TRIM(sso), ''), '') AS display_name
                    FROM sso_userid
                    WHERE project_id = ?
                      AND sso IS NOT NULL
                      AND TRIM(sso) <> ''
                ) project_ssos
                GROUP BY sso
                ORDER BY sso
                """,
                (rs, rowNum) -> new ProjectSso(
                        rs.getString("display_name"),
                        rs.getString("sso")
                ),
                projectKey,
                projectId
        ).stream().filter(item -> StringUtils.hasText(item.sso())).toList();
    }

    @Transactional
    public ProjectOption saveOrUpdateProject(String projectKey, String projectName) {
        return saveOrUpdateByKey(projectKey, projectName);
    }

    @Transactional
    public void addSso(Long projectId, String sso) {
        if (projectId == null || findById(projectId).isEmpty()) {
            throw new IllegalArgumentException("Valid project id is required.");
        }
        if (!StringUtils.hasText(sso)) {
            throw new IllegalArgumentException("SSO is required.");
        }

        String normalizedSso = sso.trim();
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sso_userid WHERE project_id = ? AND sso = ?",
                Integer.class,
                projectId,
                normalizedSso
        );
        if (existing != null && existing > 0) {
            return;
        }

        jdbcTemplate.update(
                "INSERT INTO sso_userid (project_id, sso) VALUES (?, ?)",
                projectId,
                normalizedSso
        );
    }

    private void ensureProjectsFromJiraIssues() {
        List<ProjectOption> discoveredProjects = jdbcTemplate.query(
                """
                SELECT DISTINCT
                    project_key,
                    COALESCE(NULLIF(TRIM(project_name), ''), project_key) AS project_name
                FROM jira_issue_record
                WHERE project_key IS NOT NULL
                  AND TRIM(project_key) <> ''
                ORDER BY project_key
                """,
                (rs, rowNum) -> new ProjectOption(
                        null,
                        rs.getString("project_key"),
                        rs.getString("project_name")
                )
        );

        for (ProjectOption project : discoveredProjects) {
            saveOrUpdateByKey(project.projectKey(), project.projectName());
        }
    }

    private ProjectOption saveOrUpdateByKey(String projectKey, String projectName) {
        if (!StringUtils.hasText(projectKey)) {
            throw new IllegalArgumentException("Project key is required.");
        }
        String normalizedName = StringUtils.hasText(projectName) ? projectName.trim() : projectKey.trim();
        List<ProjectOption> existing = jdbcTemplate.query(
                "SELECT " + SELECT_PROJECT_COLUMNS + " FROM project WHERE project_key = ?",
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("project_key"),
                        rs.getString("project_name")
                ),
                projectKey.trim()
        );
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO project (project_key, project_name) VALUES (?, ?)",
                    projectKey.trim(),
                    normalizedName
            );
        } else {
            ProjectOption current = existing.get(0);
            if (!normalizedName.equals(current.projectName())) {
                jdbcTemplate.update(
                        "UPDATE project SET project_name = ? WHERE id = ?",
                        normalizedName,
                        current.projectId()
                );
            }
        }
        return findByProjectKey(projectKey.trim()).orElseThrow();
    }

    private Optional<ProjectOption> findByProjectKey(String projectKey) {
        if (!StringUtils.hasText(projectKey)) {
            return Optional.empty();
        }
        List<ProjectOption> rows = jdbcTemplate.query(
                "SELECT " + SELECT_PROJECT_COLUMNS + " FROM project WHERE project_key = ?",
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("project_key"),
                        rs.getString("project_name")
                ),
                projectKey.trim()
        );
        return rows.stream().findFirst();
    }
}
