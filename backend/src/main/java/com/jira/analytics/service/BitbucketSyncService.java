package com.jira.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.analytics.dto.AddProjectSsoRequest;
import com.jira.analytics.config.BitbucketProperties;
import com.jira.analytics.dto.BitbucketCatalogRefreshResult;
import com.jira.analytics.dto.BitbucketPrKey;
import com.jira.analytics.dto.BitbucketPrRecord;
import com.jira.analytics.dto.BitbucketSyncRequest;
import com.jira.analytics.dto.BitbucketSyncResult;
import com.jira.analytics.dto.BitbucketUserMapping;
import com.jira.analytics.dto.CreateProjectRequest;
import com.jira.analytics.dto.ProjectOption;
import com.jira.analytics.dto.SyncError;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BitbucketSyncService {

    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");
    private static final List<String> REVIEW_ACTIONS = List.of("APPROVED", "COMMENTED", "REVIEWED", "ADDED_COMMENT");

    private final ProjectJdbcRepository projectRepository;
    private final SsoUserIdJdbcRepository ssoUserIdRepository;
    private final BitbucketUserLookupClient bitbucketUserLookupClient;
    private final BitbucketApiClient bitbucketApiClient;
    private final BitbucketPrJdbcRepository bitbucketPrJdbcRepository;
    private final JiraIssueLookupRepository jiraIssueLookupRepository;
    private final BitbucketCatalogService catalogService;
    private final BitbucketCatalogJdbcRepository catalogRepository;
    private final BitbucketUserRepoActivityJdbcRepository userRepoActivityRepository;
    private final BitbucketProperties properties;

    public BitbucketSyncService(
            ProjectJdbcRepository projectRepository,
            SsoUserIdJdbcRepository ssoUserIdRepository,
            BitbucketUserLookupClient bitbucketUserLookupClient,
            BitbucketApiClient bitbucketApiClient,
            BitbucketPrJdbcRepository bitbucketPrJdbcRepository,
            JiraIssueLookupRepository jiraIssueLookupRepository,
            BitbucketCatalogService catalogService,
            BitbucketCatalogJdbcRepository catalogRepository,
            BitbucketUserRepoActivityJdbcRepository userRepoActivityRepository,
            BitbucketProperties properties
    ) {
        this.projectRepository = projectRepository;
        this.ssoUserIdRepository = ssoUserIdRepository;
        this.bitbucketUserLookupClient = bitbucketUserLookupClient;
        this.bitbucketApiClient = bitbucketApiClient;
        this.bitbucketPrJdbcRepository = bitbucketPrJdbcRepository;
        this.jiraIssueLookupRepository = jiraIssueLookupRepository;
        this.catalogService = catalogService;
        this.catalogRepository = catalogRepository;
        this.userRepoActivityRepository = userRepoActivityRepository;
        this.properties = properties;
    }

    public List<ProjectOption> findProjects() {
        return projectRepository.findAll();
    }

    public ProjectOption saveProject(CreateProjectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Project details are required.");
        }
        return projectRepository.saveOrUpdateProject(request.projectKey(), request.projectName());
    }

    public List<String> findProjectSsos(Long projectId) {
        validateProject(projectId);
        return projectRepository.findSsosByProjectId(projectId);
    }

    public void addProjectSso(Long projectId, AddProjectSsoRequest request) {
        validateProject(projectId);
        if (request == null) {
            throw new IllegalArgumentException("SSO details are required.");
        }
        projectRepository.addSso(projectId, request.sso());
    }

    public BitbucketCatalogRefreshResult refreshCatalog() {
        return catalogService.refreshCatalog();
    }

    public BitbucketSyncResult sync(BitbucketSyncRequest request) {
        OffsetDateTime syncedAt = OffsetDateTime.now(ZoneOffset.UTC);
        boolean fullRefresh = request != null && Boolean.TRUE.equals(request.fullRefresh());

        ProjectOption project = validateProject(request == null ? null : request.projectId());
        DateRange dateRange = normalizeDateRange(request == null ? null : request.fromDate(), request == null ? null : request.toDate());
        List<String> selectedSsos = normalizeSsos(request == null ? null : request.ssos());
        if (selectedSsos.isEmpty()) {
            throw new IllegalStateException("Select at least one SSO before syncing Bitbucket data.");
        }

        List<String> projectSsos = projectRepository.findSsosByProjectId(project.projectId());
        LinkedHashSet<String> allowedSsos = new LinkedHashSet<>(projectSsos);
        selectedSsos.removeIf(sso -> !allowedSsos.contains(sso));
        if (selectedSsos.isEmpty()) {
            throw new IllegalStateException("Selected SSOs do not belong to the chosen project.");
        }

        BitbucketCatalogRefreshResult catalogStatus = catalogService.ensureFreshCatalog(false);
        List<BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow> repositories = catalogService.findActiveRepositories();

        List<SyncError> errors = new ArrayList<>();
        Map<String, BitbucketUserMapping> resolvedUsers = resolveUsers(project.projectId(), selectedSsos, fullRefresh, errors);
        List<String> authorFilters = resolvedUsers.values().stream()
                .map(BitbucketUserMapping::preferredAuthorFilter)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        if (authorFilters.isEmpty()) {
            return buildResult(
                    "PARTIAL_SUCCESS",
                    project.projectId(),
                    selectedSsos.size(),
                    0,
                    catalogStatus,
                    0,
                    0,
                    0,
                    errors,
                    syncedAt
            );
        }

        Map<BitbucketPrKey, DiscoveryCandidate> discovered = discoverPullRequests(repositories, authorFilters, errors);
        List<BitbucketPrRecord> normalized = new ArrayList<>();
        for (DiscoveryCandidate candidate : discovered.values()) {
            try {
                BitbucketPrRecord record = normalizePullRequest(project, candidate, resolvedUsers, fullRefresh, syncedAt, dateRange);
                if (record != null) {
                    normalized.add(record);
                }
            } catch (Exception exception) {
                errors.add(new SyncError(candidate.label(), safeMessage(exception)));
            }
        }

        BitbucketPrJdbcRepository.PersistedCounts persistedCounts = bitbucketPrJdbcRepository.saveOrUpdateAll(normalized);
        return buildResult(
                errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS",
                project.projectId(),
                selectedSsos.size(),
                resolvedUsers.size(),
                catalogStatus,
                repositories.size(),
                discovered.size(),
                persistedCounts.inserted(),
                persistedCounts.updated(),
                errors,
                syncedAt
        );
    }

    private Map<BitbucketPrKey, DiscoveryCandidate> discoverPullRequests(
            List<BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow> repositories,
            List<String> authorFilters,
            List<SyncError> errors
    ) {
        Map<BitbucketPrKey, DiscoveryCandidate> discovered = new LinkedHashMap<>();
        if (repositories.isEmpty()) {
            return discovered;
        }

        int concurrency = Math.max(1, properties.getSyncConcurrency());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<RepositoryScanResult>> futures = new ArrayList<>();
            for (BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow repository : repositories) {
                futures.add(executor.submit(() -> scanRepository(repository, authorFilters)));
            }

            for (Future<RepositoryScanResult> future : futures) {
                try {
                    RepositoryScanResult result = future.get();
                    for (DiscoveryCandidate candidate : result.candidates()) {
                        discovered.putIfAbsent(candidate.key(), candidate);
                    }
                } catch (Exception exception) {
                    errors.add(new SyncError("REPOSITORY_SCAN", safeMessage(exception)));
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return discovered;
    }

    private RepositoryScanResult scanRepository(
            BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow repository,
            List<String> authorFilters
    ) {
        Map<BitbucketPrKey, DiscoveryCandidate> deduped = new LinkedHashMap<>();
        for (List<String> batch : partition(authorFilters, properties.getParticipantBatchSize())) {
            List<JsonNode> results = bitbucketApiClient.searchPullRequests(repository.projectKey(), repository.repoSlug(), batch);
            for (JsonNode pr : results) {
                Long prId = firstLong(pr, "id", "pullRequestId", "pullRequest.id");
                if (prId == null) {
                    continue;
                }
                BitbucketPrKey key = new BitbucketPrKey(repository.projectKey(), repository.repoSlug(), prId);
                deduped.putIfAbsent(key, new DiscoveryCandidate(key, repository, pr));
            }
        }
        return new RepositoryScanResult(new ArrayList<>(deduped.values()));
    }

    private BitbucketPrRecord normalizePullRequest(
            ProjectOption applicationProject,
            DiscoveryCandidate candidate,
            Map<String, BitbucketUserMapping> resolvedUsers,
            boolean fullRefresh,
            OffsetDateTime syncedAt,
            DateRange dateRange
    ) {
        BitbucketPrJdbcRepository existingRepository = bitbucketPrJdbcRepository;
        Optional<BitbucketPrRecord> existing = existingRepository.findByNaturalKey(
                candidate.repository().projectKey(),
                candidate.repository().repoSlug(),
                candidate.key().prId()
        );

        boolean skipDetail = !fullRefresh
                && existing.isPresent()
                && "MERGED".equalsIgnoreCase(normalizeText(existing.get().state()))
                && existing.get().prCreatedAt() != null
                && existing.get().firstCommitAt() != null
                && existing.get().prMergedAt() != null;

        BitbucketPrRecord record = skipDetail
                ? mergeExistingRecord(applicationProject, candidate, existing.get(), syncedAt)
                : buildEnrichedRecord(applicationProject, candidate, resolvedUsers, syncedAt);

        if (!matchesDateRange(record, dateRange)) {
            return null;
        }

        String authorUsername = firstNonBlank(record.authorUsername(), candidate.bestAuthorFilter());
        userRepoActivityRepository.upsert(authorUsername, record.projectKey(), record.repoSlug(), record.prCreatedAt(), syncedAt);
        return record;
    }

    private BitbucketPrRecord mergeExistingRecord(
            ProjectOption applicationProject,
            DiscoveryCandidate candidate,
            BitbucketPrRecord existing,
            OffsetDateTime syncedAt
    ) {
        String projectName = firstNonBlank(existing.projectName(), lookupProjectName(candidate.repository().projectKey()));
        String repositoryName = firstNonBlank(existing.repositoryName(), candidate.repository().repoName(), candidate.repository().repoSlug());
        String title = firstNonBlank(existing.title(), firstText(candidate.summary(), "title"));
        String description = firstNonBlank(existing.description(), firstText(candidate.summary(), "description"));
        String sourceBranch = firstNonBlank(existing.sourceBranch(), firstText(candidate.summary(), "fromRef.displayId", "sourceBranch"));
        String destinationBranch = firstNonBlank(existing.destinationBranch(), firstText(candidate.summary(), "toRef.displayId", "destinationBranch"));
        String state = firstNonBlank(existing.state(), firstText(candidate.summary(), "state"));
        String authorName = firstNonBlank(existing.authorName(), firstText(candidate.summary(), "author.displayName", "author.name"));
        String authorUsername = firstNonBlank(existing.authorUsername(), firstText(candidate.summary(), "author.name", "author.slug"), candidate.bestAuthorFilter());
        String authorUserId = firstNonBlank(existing.authorUserId(), candidate.bestAuthorMapping().map(BitbucketUserMapping::bitbucketUserId).orElse(null));
        return new BitbucketPrRecord(
                existing.id(),
                applicationProject.projectId(),
                candidate.repository().projectKey(),
                projectName,
                repositoryName,
                candidate.repository().repoSlug(),
                candidate.key().prId(),
                authorName,
                authorUsername,
                authorUserId,
                title,
                description,
                sourceBranch,
                destinationBranch,
                state,
                existing.jiraKey(),
                existing.jiraMappingSource(),
                existing.prCreatedAt(),
                existing.firstCommitAt(),
                existing.firstReviewEngagementAt(),
                existing.prMergedAt(),
                existing.cycleStart(),
                existing.cycleStartSource(),
                existing.cycleTimeDays(),
                syncedAt
        );
    }

    private BitbucketPrRecord buildEnrichedRecord(
            ProjectOption applicationProject,
            DiscoveryCandidate candidate,
            Map<String, BitbucketUserMapping> resolvedUsers,
            OffsetDateTime syncedAt
    ) {
        JsonNode pullRequest = bitbucketApiClient.getPullRequest(candidate.repository().projectKey(), candidate.repository().repoSlug(), candidate.key().prId());
        List<JsonNode> commits = bitbucketApiClient.getPullRequestCommits(candidate.repository().projectKey(), candidate.repository().repoSlug(), candidate.key().prId());
        List<JsonNode> activities = bitbucketApiClient.getPullRequestActivities(candidate.repository().projectKey(), candidate.repository().repoSlug(), candidate.key().prId());

        String projectKey = candidate.repository().projectKey();
        String projectName = firstNonBlank(lookupProjectName(projectKey), firstText(pullRequest, "toRef.repository.project.name"), projectKey);
        String repositoryName = firstNonBlank(candidate.repository().repoName(), firstText(pullRequest, "toRef.repository.name"), candidate.repository().repoSlug());
        String title = firstNonBlank(firstText(pullRequest, "title"), firstText(candidate.summary(), "title"));
        String description = firstNonBlank(firstText(pullRequest, "description"), firstText(candidate.summary(), "description"));
        String sourceBranch = firstNonBlank(
                firstText(pullRequest, "fromRef.displayId"),
                firstText(pullRequest, "sourceBranch"),
                firstText(candidate.summary(), "fromRef.displayId", "sourceBranch")
        );
        String destinationBranch = firstNonBlank(
                firstText(pullRequest, "toRef.displayId"),
                firstText(pullRequest, "destinationBranch"),
                firstText(candidate.summary(), "toRef.displayId", "destinationBranch")
        );
        String state = firstNonBlank(firstText(pullRequest, "state"), firstText(candidate.summary(), "state"));
        String authorName = firstNonBlank(
                firstText(pullRequest, "author.displayName"),
                firstText(pullRequest, "author.name"),
                firstText(candidate.summary(), "author.displayName"),
                firstText(candidate.summary(), "author.name")
        );
        String authorUsername = firstNonBlank(
                firstText(pullRequest, "author.name"),
                firstText(pullRequest, "author.slug"),
                firstText(candidate.summary(), "author.name"),
                firstText(candidate.summary(), "author.slug"),
                candidate.bestAuthorFilter()
        );

        BitbucketUserMapping resolvedUser = firstResolvedUser(resolvedUsers, candidate.bestAuthorFilter()).orElse(null);
        String authorUserId = firstNonBlank(
                firstText(pullRequest, "author.id"),
                firstText(pullRequest, "author.userId"),
                resolvedUser == null ? null : resolvedUser.bitbucketUserId()
        );

        OffsetDateTime prCreatedAt = firstDateTime(pullRequest, candidate.summary(), "createdDate", "createdAt");
        OffsetDateTime firstCommitAt = firstCommitTimestamp(commits);
        OffsetDateTime firstReviewEngagementAt = firstReviewActivityTimestamp(activities);
        OffsetDateTime prMergedAt = firstMergedTimestamp(activities, pullRequest, state);

        String jiraKey = extractJiraKey(commits, title, description, sourceBranch);
        String jiraMappingSource = jiraKeySource(commits, title, description, sourceBranch, jiraKey);
        OffsetDateTime jiraInProgressAt = StringUtils.hasText(jiraKey)
                ? jiraIssueLookupRepository.findToDoToInProgressAt(jiraKey).orElse(null)
                : null;

        CycleStart cycleStart = determineCycleStart(firstCommitAt, prCreatedAt, jiraInProgressAt);
        Double cycleTimeDays = calculateCycleTimeDays(cycleStart.value(), prMergedAt);

        return new BitbucketPrRecord(
                null,
                applicationProject.projectId(),
                projectKey,
                projectName,
                repositoryName,
                candidate.repository().repoSlug(),
                candidate.key().prId(),
                authorName,
                authorUsername,
                authorUserId,
                title,
                description,
                sourceBranch,
                destinationBranch,
                state,
                jiraKey,
                jiraMappingSource,
                prCreatedAt,
                firstCommitAt,
                firstReviewEngagementAt,
                prMergedAt,
                cycleStart.value(),
                cycleStart.source(),
                cycleTimeDays,
                syncedAt
        );
    }

    private Map<String, BitbucketUserMapping> resolveUsers(
            Long projectId,
            List<String> selectedSsos,
            boolean fullRefresh,
            List<SyncError> errors
    ) {
        Map<String, BitbucketUserMapping> resolved = new LinkedHashMap<>();
        for (String sso : selectedSsos) {
            Optional<SsoUserIdJdbcRepository.SsoUserIdMapping> existing = ssoUserIdRepository.findByProjectIdAndSso(projectId, sso);
            if (!fullRefresh && existing.isPresent() && toUserMapping(existing.get()).isValid() && !isMappingStale(existing.get())) {
                resolved.put(sso, toUserMapping(existing.get()));
                continue;
            }

            try {
                Optional<BitbucketUserMapping> lookup = bitbucketUserLookupClient.resolveBitbucketUser(sso);
                if (lookup.isEmpty()) {
                    errors.add(new SyncError(sso, "Unable to resolve Bitbucket user mapping"));
                    continue;
                }
                SsoUserIdJdbcRepository.SsoUserIdMapping saved = ssoUserIdRepository.saveOrUpdate(projectId, sso, lookup.get());
                resolved.put(sso, new BitbucketUserMapping(
                        saved.bitbucketUserId(),
                        saved.bitbucketUsername(),
                        saved.bitbucketSlug(),
                        saved.lastResolvedAt()
                ));
            } catch (Exception exception) {
                errors.add(new SyncError(sso, safeMessage(exception)));
            }
        }
        return resolved;
    }

    private boolean isMappingStale(SsoUserIdJdbcRepository.SsoUserIdMapping mapping) {
        if (mapping == null || mapping.lastResolvedAt() == null) {
            return true;
        }
        int ttlHours = Math.max(1, properties.getUserLookupTtlHours());
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusHours(ttlHours);
        return mapping.lastResolvedAt().isBefore(threshold);
    }

    private List<String> normalizeSsos(List<String> ssos) {
        if (ssos == null) {
            return List.of();
        }
        return ssos.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private ProjectOption validateProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalStateException("Select a project before syncing Bitbucket data.");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("Unknown project id: " + projectId));
    }

    private DateRange normalizeDateRange(String fromDate, String toDate) {
        OffsetDateTime from = parseDateStart(fromDate);
        OffsetDateTime to = parseDateEnd(toDate);
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalStateException("toDate must be on or after fromDate.");
        }
        return new DateRange(from, to);
    }

    private OffsetDateTime parseDateStart(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("fromDate must use yyyy-MM-dd format.");
        }
    }

    private OffsetDateTime parseDateEnd(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("toDate must use yyyy-MM-dd format.");
        }
    }

    private boolean matchesDateRange(BitbucketPrRecord record, DateRange dateRange) {
        if (dateRange == null) {
            return true;
        }
        boolean createdMatches = withinRange(record.prCreatedAt(), dateRange);
        boolean mergedMatches = withinRange(record.prMergedAt(), dateRange);
        return createdMatches || mergedMatches;
    }

    private boolean withinRange(OffsetDateTime value, DateRange dateRange) {
        if (value == null || dateRange == null) {
            return false;
        }
        if (dateRange.from() != null && value.isBefore(dateRange.from())) {
            return false;
        }
        return dateRange.to() == null || !value.isAfter(dateRange.to());
    }

    private String lookupProjectName(String projectKey) {
        if (!StringUtils.hasText(projectKey)) {
            return null;
        }
        return catalogRepository.findProjectByKey(projectKey)
                .map(BitbucketCatalogJdbcRepository.BitbucketProjectCatalogRow::projectName)
                .orElse(projectKey);
    }

    private Optional<BitbucketUserMapping> firstResolvedUser(Map<String, BitbucketUserMapping> resolvedUsers, String authorFilter) {
        if (!StringUtils.hasText(authorFilter)) {
            return Optional.empty();
        }
        return resolvedUsers.values().stream()
                .filter(mapping -> authorFilter.equalsIgnoreCase(mapping.preferredAuthorFilter()))
                .findFirst();
    }

    private BitbucketUserMapping toUserMapping(SsoUserIdJdbcRepository.SsoUserIdMapping mapping) {
        return new BitbucketUserMapping(
                mapping.bitbucketUserId(),
                mapping.bitbucketUsername(),
                mapping.bitbucketSlug(),
                mapping.lastResolvedAt()
        );
    }

    private BitbucketSyncResult buildResult(
            String status,
            Long projectId,
            int ssosRequested,
            int usersResolved,
            BitbucketCatalogRefreshResult catalogStatus,
            int repositoriesScanned,
            int prsDiscovered,
            int prsInserted,
            int prsUpdated,
            List<SyncError> errors,
            OffsetDateTime syncedAt
    ) {
        return new BitbucketSyncResult(
                status,
                projectId,
                ssosRequested,
                usersResolved,
                catalogStatus.status(),
                catalogStatus.projectsDiscovered(),
                catalogStatus.repositoriesDiscovered(),
                repositoriesScanned,
                prsDiscovered,
                prsInserted,
                prsUpdated,
                errors,
                syncedAt.toString()
        );
    }

    private BitbucketSyncResult buildResult(
            String status,
            Long projectId,
            int ssosRequested,
            int usersResolved,
            BitbucketCatalogRefreshResult catalogStatus,
            int repositoriesScanned,
            int prsDiscovered,
            int prsInserted,
            List<SyncError> errors,
            OffsetDateTime syncedAt
    ) {
        return buildResult(status, projectId, ssosRequested, usersResolved, catalogStatus, repositoriesScanned, prsDiscovered, prsInserted, 0, errors, syncedAt);
    }

    private List<List<String>> partition(List<String> values, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return batches;
        }
        int size = Math.max(1, batchSize);
        for (int index = 0; index < values.size(); index += size) {
            batches.add(values.subList(index, Math.min(values.size(), index + size)));
        }
        return batches;
    }

    private CycleStart determineCycleStart(OffsetDateTime firstCommitAt, OffsetDateTime prCreatedAt, OffsetDateTime jiraInProgressAt) {
        List<Candidate> candidates = new ArrayList<>();
        if (firstCommitAt != null) {
            candidates.add(new Candidate(firstCommitAt, "BB FIRST COMMIT"));
        }
        if (prCreatedAt != null) {
            candidates.add(new Candidate(prCreatedAt, "BB PR CREATED"));
        }
        if (jiraInProgressAt != null) {
            candidates.add(new Candidate(jiraInProgressAt, "JIRA IN PROGRESS"));
        }
        if (candidates.isEmpty()) {
            return new CycleStart(null, null);
        }
        Candidate earliest = candidates.stream()
                .min(Comparator.comparing(Candidate::value))
                .orElseThrow();
        return new CycleStart(earliest.value(), earliest.source());
    }

    private Double calculateCycleTimeDays(OffsetDateTime cycleStart, OffsetDateTime prMergedAt) {
        if (cycleStart == null || prMergedAt == null || prMergedAt.isBefore(cycleStart)) {
            return null;
        }
        return Duration.between(cycleStart, prMergedAt).toMinutes() / 1440.0d;
    }

    private OffsetDateTime firstCommitTimestamp(List<JsonNode> commits) {
        return commits.stream()
                .map(this::extractCommitTimestamp)
                .filter(value -> value != null)
                .min(OffsetDateTime::compareTo)
                .orElse(null);
    }

    private OffsetDateTime firstReviewActivityTimestamp(List<JsonNode> activities) {
        return activities.stream()
                .filter(activity -> {
                    String action = normalizeText(firstText(activity, "action"));
                    return REVIEW_ACTIONS.stream().anyMatch(review -> review.equalsIgnoreCase(action));
                })
                .map(this::extractActivityTimestamp)
                .filter(value -> value != null)
                .min(OffsetDateTime::compareTo)
                .orElse(null);
    }

    private OffsetDateTime firstMergedTimestamp(List<JsonNode> activities, JsonNode pullRequest, String state) {
        OffsetDateTime mergedAt = activities.stream()
                .filter(activity -> "MERGED".equalsIgnoreCase(normalizeText(firstText(activity, "action"))))
                .map(this::extractActivityTimestamp)
                .filter(value -> value != null)
                .min(OffsetDateTime::compareTo)
                .orElse(null);
        if (mergedAt != null) {
            return mergedAt;
        }
        if ("MERGED".equalsIgnoreCase(normalizeText(state))) {
            return firstDateTime(pullRequest, pullRequest, "closedDate", "closedAt", "pullRequest.closedDate");
        }
        return null;
    }

    private String extractJiraKey(List<JsonNode> commits, String... candidates) {
        for (JsonNode commit : commits) {
            String jiraKey = firstText(commit, "properties.jira-key", "commit.properties.jira-key", "properties['jira-key']");
            if (StringUtils.hasText(jiraKey)) {
                return jiraKey.trim();
            }
        }

        for (String candidate : candidates) {
            String jiraKey = regexExtract(candidate);
            if (StringUtils.hasText(jiraKey)) {
                return jiraKey;
            }
        }

        for (JsonNode commit : commits) {
            String message = firstText(commit, "message", "commit.message", "commit.message.value");
            String jiraKey = regexExtract(message);
            if (StringUtils.hasText(jiraKey)) {
                return jiraKey;
            }
        }

        return null;
    }

    private String jiraKeySource(List<JsonNode> commits, String title, String description, String sourceBranch, String jiraKey) {
        if (!StringUtils.hasText(jiraKey)) {
            return "NONE";
        }

        for (JsonNode commit : commits) {
            String key = firstText(commit, "properties.jira-key", "commit.properties.jira-key", "properties['jira-key']");
            if (StringUtils.hasText(key) && key.trim().equalsIgnoreCase(jiraKey)) {
                return "COMMIT_PROPERTY";
            }
        }

        if (StringUtils.hasText(regexExtract(title)) && regexExtract(title).equalsIgnoreCase(jiraKey)) {
            return "PR_TITLE";
        }
        if (StringUtils.hasText(regexExtract(description)) && regexExtract(description).equalsIgnoreCase(jiraKey)) {
            return "PR_DESCRIPTION";
        }
        if (StringUtils.hasText(regexExtract(sourceBranch)) && regexExtract(sourceBranch).equalsIgnoreCase(jiraKey)) {
            return "SOURCE_BRANCH";
        }

        for (JsonNode commit : commits) {
            String message = firstText(commit, "message", "commit.message", "commit.message.value");
            String extracted = regexExtract(message);
            if (StringUtils.hasText(extracted) && extracted.equalsIgnoreCase(jiraKey)) {
                return "COMMIT_MESSAGE";
            }
        }

        return "NONE";
    }

    private String regexExtract(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = JIRA_KEY_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private OffsetDateTime extractCommitTimestamp(JsonNode commit) {
        Long epochMillis = firstLong(commit, "committerTimestamp", "commit.committerTimestamp", "commit.committer.timestamp", "authorTimestamp", "date");
        if (epochMillis == null) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private OffsetDateTime extractActivityTimestamp(JsonNode activity) {
        Long epochMillis = firstLong(activity, "createdDate", "createdAt", "activity.createdDate", "activity.createdAt");
        if (epochMillis == null) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private OffsetDateTime firstDateTime(JsonNode primary, JsonNode secondary, String... pathCandidates) {
        Long epoch = firstLong(primary, pathCandidates);
        if (epoch == null) {
            epoch = firstLong(secondary, pathCandidates);
        }
        return epoch == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneOffset.UTC);
    }

    private Long firstLong(JsonNode node, String... pathCandidates) {
        if (node == null || pathCandidates == null) {
            return null;
        }
        for (String candidate : pathCandidates) {
            JsonNode current = navigate(node, candidate);
            if (current != null && current.isNumber()) {
                return current.asLong();
            }
            if (current != null && current.isTextual()) {
                try {
                    return Long.parseLong(current.asText().trim());
                } catch (NumberFormatException ignored) {
                    // Continue.
                }
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... pathCandidates) {
        if (node == null || pathCandidates == null) {
            return null;
        }
        for (String candidate : pathCandidates) {
            JsonNode current = navigate(node, candidate);
            if (current != null && !current.isNull() && !current.isMissingNode()) {
                String value = current.asText(null);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static String firstTextValue(JsonNode node, String... pathCandidates) {
        if (node == null || pathCandidates == null) {
            return null;
        }
        for (String candidate : pathCandidates) {
            JsonNode current = node;
            for (String segment : candidate.split("\\.")) {
                if (current == null) {
                    current = null;
                    break;
                }
                current = current.path(segment);
            }
            if (current != null && !current.isNull() && !current.isMissingNode()) {
                String value = current.asText(null);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private JsonNode navigate(JsonNode node, String path) {
        if (node == null || !StringUtils.hasText(path)) {
            return null;
        }
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.path(segment);
        }
        return current;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (StringUtils.hasText(message)) {
            return message;
        }
        return exception.getClass().getSimpleName();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record DateRange(OffsetDateTime from, OffsetDateTime to) {
    }

    private record Candidate(OffsetDateTime value, String source) {
    }

    private record CycleStart(OffsetDateTime value, String source) {
    }

    private record DiscoveryCandidate(BitbucketPrKey key, BitbucketCatalogJdbcRepository.BitbucketRepositoryCatalogRow repository, JsonNode summary) {
        String label() {
            return key.projectKey() + "/" + key.repoSlug() + "#" + key.prId();
        }

        String bestAuthorFilter() {
            return firstTextValue(summary, "author.name", "author.slug");
        }

        Optional<BitbucketUserMapping> bestAuthorMapping() {
            return Optional.empty();
        }
    }

    private record RepositoryScanResult(List<DiscoveryCandidate> candidates) {
    }
}
