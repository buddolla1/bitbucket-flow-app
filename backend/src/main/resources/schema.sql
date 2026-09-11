CREATE TABLE IF NOT EXISTS jira_issue_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_id VARCHAR(64),
    issue_key VARCHAR(64),
    issue_type VARCHAR(128),
    status VARCHAR(128),
    project_key VARCHAR(128),
    project_name VARCHAR(256),
    summary VARCHAR(2000),
    story_points DOUBLE,
    sprint VARCHAR(512),
    assignee VARCHAR(256),
    sso VARCHAR(256),
    resolved_at DATETIME(6),
    month_number INTEGER,
    month_name VARCHAR(64),
    to_do_to_in_progress_at DATETIME(6),
    in_progress_to_validation_at DATETIME(6),
    validation_to_done_at DATETIME(6),
    jira_cycle_time_days DOUBLE,
    jira_updated_at DATETIME(6),
    last_synced_at DATETIME(6),
    UNIQUE KEY uk_jira_issue_record_issue_id (issue_id),
    UNIQUE KEY uk_jira_issue_record_issue_key (issue_key),
    KEY idx_jira_issue_record_project (project_key),
    KEY idx_jira_issue_record_sso (sso)
);

CREATE TABLE IF NOT EXISTS project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    project_name VARCHAR(256) NOT NULL,
    UNIQUE KEY uk_project_project_key (project_key)
);

CREATE TABLE IF NOT EXISTS sso_userid (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    sso VARCHAR(255) NOT NULL,
    bitbucket_user_id VARCHAR(100),
    bitbucket_username VARCHAR(255),
    bitbucket_slug VARCHAR(255),
    last_resolved_at DATETIME(6),
    UNIQUE KEY uk_sso_userid (project_id, sso),
    KEY idx_sso_userid_project (project_id),
    KEY idx_sso_userid_bitbucket (bitbucket_user_id)
);

CREATE TABLE IF NOT EXISTS bitbucket_pr (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_project_id BIGINT,
    project_id BIGINT,
    project_key VARCHAR(255) NOT NULL,
    project_name VARCHAR(500),
    repository_name VARCHAR(500),
    repo_slug VARCHAR(500) NOT NULL,
    pr_id BIGINT NOT NULL,
    author_name VARCHAR(500),
    author_username VARCHAR(255),
    author_user_id VARCHAR(100),
    title VARCHAR(2000),
    description LONGTEXT,
    source_branch VARCHAR(1000),
    destination_branch VARCHAR(1000),
    state VARCHAR(100),
    jira_key VARCHAR(100),
    jira_mapping_source VARCHAR(100),
    pr_created_at DATETIME(6),
    first_commit_at DATETIME(6),
    first_review_engagement_at DATETIME(6),
    pr_merged_at DATETIME(6),
    cycle_start DATETIME(6),
    cycle_start_source VARCHAR(64),
    cycle_time_days DOUBLE,
    last_synced_at DATETIME(6),
    UNIQUE KEY uk_bitbucket_pr (project_key, repo_slug, pr_id),
    KEY idx_bitbucket_pr_project (application_project_id)
);
