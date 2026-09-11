package com.jira.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.analytics.config.BitbucketProperties;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BitbucketSyncServiceEnrichmentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BitbucketSyncService service = new BitbucketSyncService(
            null,
            null,
            null,
            null,
            null,
            null,
            new BitbucketProperties()
    );

    @Test
    void jiraKeyPrefersBranchThenTitleThenDescriptionThenCommits() throws Exception {
        JsonNode commit = objectMapper.readTree("""
                {"properties":{"jira-key":"COMMIT-4"},"message":"Commit mentions COMMIT-5"}
                """);

        String jiraKey = invokeExtractJiraKey("feature/BRANCH-1-work", "TITLE-2", "DESC-3", List.of(commit));
        String source = invokeJiraKeySource("feature/BRANCH-1-work", "TITLE-2", "DESC-3", List.of(commit), jiraKey);

        assertEquals("BRANCH-1", jiraKey);
        assertEquals("SOURCE_BRANCH", source);
    }

    @Test
    void firstReviewDateIgnoresAuthorActivity() throws Exception {
        JsonNode authorComment = objectMapper.readTree("""
                {"action":"COMMENTED","createdDate":1000,"comment":{"author":{"name":"alice"}}}
                """);
        JsonNode reviewerApproval = objectMapper.readTree("""
                {"action":"APPROVED","createdDate":2000,"user":{"name":"bob"}}
                """);

        OffsetDateTime firstReview = invokeFirstReviewActivityTimestamp(List.of(authorComment, reviewerApproval), "alice");

        assertEquals(OffsetDateTime.ofInstant(Instant.ofEpochMilli(2000), ZoneOffset.UTC), firstReview);
    }

    @SuppressWarnings("unchecked")
    private String invokeExtractJiraKey(String branch, String title, String description, List<JsonNode> commits) throws Exception {
        Method method = BitbucketSyncService.class.getDeclaredMethod("extractJiraKey", String.class, String.class, String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(service, branch, title, description, commits);
    }

    private String invokeJiraKeySource(String branch, String title, String description, List<JsonNode> commits, String jiraKey) throws Exception {
        Method method = BitbucketSyncService.class.getDeclaredMethod("jiraKeySource", String.class, String.class, String.class, List.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, branch, title, description, commits, jiraKey);
    }

    private OffsetDateTime invokeFirstReviewActivityTimestamp(List<JsonNode> activities, String authorUsername) throws Exception {
        Method method = BitbucketSyncService.class.getDeclaredMethod("firstReviewActivityTimestamp", List.class, String.class);
        method.setAccessible(true);
        return (OffsetDateTime) method.invoke(service, activities, authorUsername);
    }
}
