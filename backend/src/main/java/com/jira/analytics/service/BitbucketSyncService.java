package com.jira.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.analytics.dto.AddProjectSsoRequest;
import com.jira.analytics.config.BitbucketProperties;
import com.jira.analytics.dto.BitbucketPrAnalyticsOptions;
import com.jira.analytics.dto.BitbucketPrAnalyticsPage;
import com.jira.analytics.dto.BitbucketPrAnalyticsQuery;
import com.jira.analytics.dto.BitbucketPrAnalyticsSummary;
import com.jira.analytics.dto.BitbucketPrKey;
import com.jira.analytics.dto.BitbucketPrRecord;
import com.jira.analytics.dto.BitbucketSyncRequest;
import com.jira.analytics.dto.BitbucketSyncResult;
import com.jira.analytics.dto.BitbucketUserMapping;
import com.jira.analytics.dto.ProjectOption;
import com.jira.analytics.dto.ProjectSso;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BitbucketSyncService {

    private static final Logger log = LoggerFactory.getLogger(BitbucketSyncService.class);

    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");
    private static final List<String> REVIEW_ACTIONS = List.of("APPROVED", "COMMENTED", "REVIEWED", "ADDED_COMMENT");
    private static final int STALE_OPEN_DAYS = 7;

    private final ProjectJdbcRepository projectRepository;
    private final SsoUserIdJdbcRepository ssoUserIdRepository;
    private final BitbucketUserLookupClient bitbucketUserLookupClient;
    private final BitbucketApiClient bitbucketApiClient;
    private final BitbucketPrJdbcRepository bitbucketPrJdbcRepository;
    private final JiraIssueLookupRepository jiraIssueLookupRepository;
    private final BitbucketProperties properties;

    public BitbucketSyncService(
            ProjectJdbcRepository projectRepository,
            SsoUserIdJdbcRepository ssoUserIdRepository,
            BitbucketUserLookupClient bitbucketUserLookupClient,
            BitbucketApiClient bitbucketApiClient,
            BitbucketPrJdbcRepository bitbucketPrJdbcRepository,
            JiraIssueLookupRepository jiraIssueLookupRepository,
            BitbucketProperties properties
    ) {
        this.projectRepository = projectRepository;
        this.ssoUserIdRepository = ssoUserIdRepository;
        this.bitbucketUserLookupClient = bitbucketUserLookupClient;
        this.bitbucketApiClient = bitbucketApiClient;
        this.bitbucketPrJdbcRepository = bitbucketPrJdbcRepository;
        this.jiraIssueLookupRepository = jiraIssueLookupRepository;
        this.properties = properties;
    }

    public List<ProjectOption> findProjects() {
        List<ProjectOption> projects = projectRepository.findAll();
        log.info("Returning {} projects", projects.size());
        return projects;
    }

    public List<BitbucketPrRecord> findSyncedPullRequests(Long projectId) {
        if (projectId != null) {
            validateProject(projectId);
        }
        List<BitbucketPrRecord> records = bitbucketPrJdbcRepository.findSyncedRecords(projectId);
        log.info("Returning {} synced pull requests projectId={}", records.size(), projectId);
        return records;
    }

    public BitbucketPrAnalyticsPage findPullRequestAnalytics(BitbucketPrAnalyticsQuery query) {
        BitbucketPrAnalyticsQuery normalized = normalizeAnalyticsQuery(query);
        log.info(
                "Finding PR analytics projectId={} page={} size={} sort={} direction={}",
                normalized.projectId(),
                normalized.page(),
                normalized.size(),
                normalized.sort(),
                normalized.direction()
        );
        if (normalized.projectId() != null) {
            validateProject(normalized.projectId());
        }
        long total = bitbucketPrJdbcRepository.countAnalyticsRecords(normalized);
        List<BitbucketPrRecord> records = bitbucketPrJdbcRepository.findAnalyticsRecords(normalized);
        List<BitbucketPrRecord> summaryRecords = bitbucketPrJdbcRepository.findAnalyticsSummaryRecords(normalized);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalized.size());
        BitbucketPrAnalyticsPage page = new BitbucketPrAnalyticsPage(
                records,
                total,
                normalized.page(),
                normalized.size(),
                totalPages,
                buildAnalyticsSummary(summaryRecords),
                buildAnalyticsOptions(summaryRecords)
        );
        log.info("Returning PR analytics records={} totalRecords={} totalPages={}", records.size(), total, totalPages);
        return page;
    }

    public List<ProjectSso> findProjectSsos(Long projectId) {
        validateProject(projectId);
        List<ProjectSso> ssos = projectRepository.findSsosByProjectId(projectId);
        log.info("Returning {} project SSOs projectId={}", ssos.size(), projectId);
        return ssos;
    }

    public void addProjectSso(Long projectId, AddProjectSsoRequest request) {
        log.info("Adding project SSO projectId={}", projectId);
        validateProject(projectId);
        if (request == null) {
            throw new IllegalArgumentException("SSO details are required.");
        }
        projectRepository.addSso(projectId, request.sso());
    }

    public BitbucketSyncResult sync(BitbucketSyncRequest request) {
        OffsetDateTime syncedAt = OffsetDateTime.now(ZoneOffset.UTC);
        boolean fullRefresh = request != null && Boolean.TRUE.equals(request.fullRefresh());
        log.info("Starting Bitbucket sync fullRefresh={}", fullRefresh);

        ProjectOption project = validateProject(request == null ? null : request.projectId());
        DateRange dateRange = normalizeDateRange(request == null ? null : request.fromDate(), request == null ? null : request.toDate());
        List<String> selectedSsos = normalizeSsos(request == null ? null : request.ssos());
        if (selectedSsos.isEmpty()) {
            throw new IllegalStateException("Select at least one SSO before syncing Bitbucket data.");
        }
        log.info("Normalized sync request projectId={} selectedSsoCount={}", project.projectId(), selectedSsos.size());

        List<String> projectSsos = projectRepository.findSsosByProjectId(project.projectId()).stream()
                .map(ProjectSso::sso)
                .toList();
        LinkedHashSet<String> allowedSsos = new LinkedHashSet<>(projectSsos);
        selectedSsos.removeIf(sso -> !allowedSsos.contains(sso));
        if (selectedSsos.isEmpty()) {
            throw new IllegalStateException("Selected SSOs do not belong to the chosen project.");
        }
        log.info("Validated sync SSOs projectId={} allowedSsoCount={} selectedSsoCount={}", project.projectId(), allowedSsos.size(), selectedSsos.size());

        List<SyncError> errors = new ArrayList<>();
        Map<String, BitbucketUserMapping> resolvedUsers = resolveUsers(project.projectId(), selectedSsos, fullRefresh, errors);
        List<BitbucketUserMapping> usersToScan = resolvedUsers.values().stream()
                .filter(mapping -> StringUtils.hasText(mapping.preferredAuthorFilter()))
                .toList();

        if (usersToScan.isEmpty()) {
            log.info("No Bitbucket users resolved for sync projectId={} errors={}", project.projectId(), errors.size());
            return buildResult(
                    "PARTIAL_SUCCESS",
                    project.projectId(),
                    selectedSsos.size(),
                    0,
                    0,
                    0,
                    0,
                    errors,
                    syncedAt
            );
        }

        Map<BitbucketPrKey, DiscoveryCandidate> discovered = discoverPullRequests(usersToScan, errors);
        int repositoriesScanned = (int) discovered.keySet().stream()
                .map(key -> key.projectKey() + "/" + key.repoSlug())
                .distinct()
                .count();
        List<BitbucketPrRecord> normalized = enrichPullRequests(
                project,
                new ArrayList<>(discovered.values()),
                resolvedUsers,
                fullRefresh,
                syncedAt,
                dateRange,
                errors
        );

        BitbucketPrJdbcRepository.PersistedCounts persistedCounts = bitbucketPrJdbcRepository.saveOrUpdateAll(normalized);
        BitbucketSyncResult result = buildResult(
                errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS",
                project.projectId(),
                selectedSsos.size(),
                resolvedUsers.size(),
                repositoriesScanned,
                discovered.size(),
                persistedCounts.inserted(),
                persistedCounts.updated(),
                errors,
                syncedAt
        );
        log.info(
                "Completed Bitbucket sync projectId={} status={} discovered={} normalized={} inserted={} updated={} errors={}",
                project.projectId(),
                result.status(),
                discovered.size(),
                normalized.size(),
                persistedCounts.inserted(),
                persistedCounts.updated(),
                errors.size()
        );
        return result;
    }

    private Map<BitbucketPrKey, DiscoveryCandidate> discoverPullRequests(
            List<BitbucketUserMapping> usersToScan,
            List<SyncError> errors
    ) {
        Map<BitbucketPrKey, DiscoveryCandidate> discovered = new LinkedHashMap<>();
        if (usersToScan.isEmpty()) {
            return discovered;
        }

        int concurrency = Math.max(1, properties.getSyncConcurrency());
        log.info("Discovering pull requests userCount={} concurrency={}", usersToScan.size(), concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<UserPullRequestScanResult>> futures = new ArrayList<>();
            for (BitbucketUserMapping user : usersToScan) {
                futures.add(executor.submit(() -> scanUserPullRequests(user)));
            }

            for (Future<UserPullRequestScanResult> future : futures) {
                try {
                    UserPullRequestScanResult result = future.get();
                    for (DiscoveryCandidate candidate : result.candidates()) {
                        discovered.putIfAbsent(candidate.key(), candidate);
                    }
                } catch (Exception exception) {
                    errors.add(new SyncError("USER_PR_SCAN", safeMessage(exception)));
                }
            }
        } finally {
            executor.shutdownNow();
        }

        log.info("Discovered {} unique Bitbucket pull requests errors={}", discovered.size(), errors.size());
        return discovered;
    }

    private List<BitbucketPrRecord> enrichPullRequests(
            ProjectOption project,
            List<DiscoveryCandidate> candidates,
            Map<String, BitbucketUserMapping> resolvedUsers,
            boolean fullRefresh,
            OffsetDateTime syncedAt,
            DateRange dateRange,
            List<SyncError> errors
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        int concurrency = Math.min(candidates.size(), Math.max(1, properties.getSyncConcurrency()));
        log.info("Enriching pull requests candidateCount={} concurrency={}", candidates.size(), concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CompletionService<PrEnrichmentResult> completionService = new ExecutorCompletionService<>(executor);
        try {
            for (DiscoveryCandidate candidate : candidates) {
                completionService.submit(() -> enrichPullRequest(project, candidate, resolvedUsers, fullRefresh, syncedAt, dateRange));
            }

            List<BitbucketPrRecord> records = new ArrayList<>();
            for (int completed = 0; completed < candidates.size(); completed++) {
                try {
                    PrEnrichmentResult result = completionService.take().get();
                    if (result.record() != null) {
                        records.add(result.record());
                    }
                    if (result.error() != null) {
                        errors.add(result.error());
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errors.add(new SyncError("PR_ENRICHMENT", "Interrupted while enriching pull requests."));
                    break;
                } catch (Exception exception) {
                    errors.add(new SyncError("PR_ENRICHMENT", safeMessage(exception)));
                }
            }
            log.info("Enriched {} pull requests errors={}", records.size(), errors.size());
            return records;
        } finally {
            executor.shutdownNow();
        }
    }

    private PrEnrichmentResult enrichPullRequest(
            ProjectOption project,
            DiscoveryCandidate candidate,
            Map<String, BitbucketUserMapping> resolvedUsers,
            boolean fullRefresh,
            OffsetDateTime syncedAt,
            DateRange dateRange
    ) {
        try {
            BitbucketPrRecord record = normalizePullRequest(project, candidate, resolvedUsers, fullRefresh, syncedAt, dateRange);
            return new PrEnrichmentResult(record, null);
        } catch (Exception exception) {
            return new PrEnrichmentResult(null, new SyncError(candidate.label(), safeMessage(exception)));
        }
    }

    private UserPullRequestScanResult scanUserPullRequests(BitbucketUserMapping user) {
        Map<BitbucketPrKey, DiscoveryCandidate> deduped = new LinkedHashMap<>();
        List<JsonNode> results = bitbucketApiClient.getUserPullRequests(user.preferredAuthorFilter());
        for (JsonNode pr : results) {
            Long prId = firstLong(pr, "id", "pullRequestId", "pullRequest.id");
            String projectKey = firstNonBlank(
                    firstText(pr, "toRef.repository.project.key"),
                    firstText(pr, "fromRef.repository.project.key"),
                    firstText(pr, "repository.project.key"),
                    firstText(pr, "project.key"),
                    firstText(pr, "projectKey")
            );
            String repoSlug = firstNonBlank(
                    firstText(pr, "toRef.repository.slug"),
                    firstText(pr, "fromRef.repository.slug"),
                    firstText(pr, "repository.slug"),
                    firstText(pr, "repoSlug"),
                    firstText(pr, "repositorySlug")
            );
            if (prId == null || !StringUtils.hasText(projectKey) || !StringUtils.hasText(repoSlug)) {
                continue;
            }
            String repoName = firstNonBlank(
                    firstText(pr, "toRef.repository.name"),
                    firstText(pr, "fromRef.repository.name"),
                    firstText(pr, "repository.name"),
                    repoSlug
            );
            RepositorySummary repository = new RepositorySummary(projectKey, repoSlug, repoName);
            BitbucketPrKey key = new BitbucketPrKey(projectKey, repoSlug, prId);
            deduped.putIfAbsent(key, new DiscoveryCandidate(key, repository, pr, user));
        }
        log.info("Scanned user pull requests user={} candidates={}", user.preferredAuthorFilter(), deduped.size());
        return new UserPullRequestScanResult(new ArrayList<>(deduped.values()));
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
                && existing.get().firstCommitAt() != null
                && existing.get().prMergedAt() != null
                && existing.get().cycleStart() != null;

        BitbucketPrRecord record = skipDetail
                ? mergeExistingRecord(applicationProject, candidate, existing.get(), syncedAt)
                : buildEnrichedRecord(applicationProject, candidate, resolvedUsers, syncedAt);

        if (!matchesDateRange(record, dateRange)) {
            return null;
        }

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
        String authorUserId = firstNonBlank(existing.authorUserId(), candidate.authorMapping().bitbucketUserId());
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
        OffsetDateTime firstReviewEngagementAt = firstReviewActivityTimestamp(activities, authorUsername);
        OffsetDateTime prMergedAt = firstMergedTimestamp(activities, pullRequest, state);

        String jiraKey = extractJiraKey(sourceBranch, title, description, commits);
        String jiraMappingSource = jiraKeySource(sourceBranch, title, description, commits, jiraKey);
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
        if (selectedSsos.isEmpty()) {
            return resolved;
        }

        int concurrency = Math.min(selectedSsos.size(), Math.max(1, properties.getSyncConcurrency()));
        log.info("Resolving Bitbucket users selectedSsoCount={} concurrency={} fullRefresh={}", selectedSsos.size(), concurrency, fullRefresh);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CompletionService<UserResolutionResult> completionService = new ExecutorCompletionService<>(executor);
        try {
            for (String sso : selectedSsos) {
                completionService.submit(() -> resolveUser(projectId, sso, fullRefresh));
            }
            for (int completed = 0; completed < selectedSsos.size(); completed++) {
                try {
                    UserResolutionResult result = completionService.take().get();
                    if (result.mapping() != null) {
                        resolved.put(result.sso(), result.mapping());
                    }
                    if (result.error() != null) {
                        errors.add(result.error());
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errors.add(new SyncError("USER_RESOLUTION", "Interrupted while resolving Bitbucket users."));
                    break;
                } catch (Exception exception) {
                    errors.add(new SyncError("USER_RESOLUTION", safeMessage(exception)));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        log.info("Resolved {} Bitbucket users errors={}", resolved.size(), errors.size());
        return resolved;
    }

    private UserResolutionResult resolveUser(Long projectId, String sso, boolean fullRefresh) {
        Optional<SsoUserIdJdbcRepository.SsoUserIdMapping> existing = ssoUserIdRepository.findByProjectIdAndSso(projectId, sso);
        if (!fullRefresh && existing.isPresent() && toUserMapping(existing.get()).isValid() && !isMappingStale(existing.get())) {
            log.info("Using cached Bitbucket user mapping projectId={} sso={}", projectId, sso);
            return new UserResolutionResult(sso, toUserMapping(existing.get()), null);
        }

        try {
            Optional<BitbucketUserMapping> lookup = bitbucketUserLookupClient.resolveBitbucketUser(sso);
            if (lookup.isEmpty()) {
                log.info("Bitbucket user mapping not found projectId={} sso={}", projectId, sso);
                return new UserResolutionResult(sso, null, new SyncError(sso, "Unable to resolve Bitbucket user mapping"));
            }
            SsoUserIdJdbcRepository.SsoUserIdMapping saved = ssoUserIdRepository.saveOrUpdate(projectId, sso, lookup.get());
            log.info("Resolved and saved Bitbucket user mapping projectId={} sso={}", projectId, sso);
            return new UserResolutionResult(sso, new BitbucketUserMapping(
                    saved.bitbucketUserId(),
                    saved.bitbucketUsername(),
                    saved.bitbucketSlug(),
                    saved.lastResolvedAt()
            ), null);
        } catch (Exception exception) {
            log.info("Failed to resolve Bitbucket user mapping projectId={} sso={} error={}", projectId, sso, safeMessage(exception));
            return new UserResolutionResult(sso, null, new SyncError(sso, safeMessage(exception)));
        }
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
        ProjectOption project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("Unknown project id: " + projectId));
        log.info("Validated project projectId={} projectKey={}", project.projectId(), project.projectKey());
        return project;
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
        return StringUtils.hasText(projectKey) ? projectKey.trim() : null;
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
            int repositoriesScanned,
            int prsDiscovered,
            int prsInserted,
            List<SyncError> errors,
            OffsetDateTime syncedAt
    ) {
        return buildResult(status, projectId, ssosRequested, usersResolved, repositoriesScanned, prsDiscovered, prsInserted, 0, errors, syncedAt);
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

    private OffsetDateTime firstReviewActivityTimestamp(List<JsonNode> activities, String authorUsername) {
        return activities.stream()
                .filter(activity -> {
                    String action = normalizeText(firstText(activity, "action"));
                    return REVIEW_ACTIONS.stream().anyMatch(review -> review.equalsIgnoreCase(action));
                })
                .filter(activity -> !sameUser(authorUsername, activityActor(activity)))
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

    private String extractJiraKey(String sourceBranch, String title, String description, List<JsonNode> commits) {
        for (String candidate : new String[] {sourceBranch, title, description}) {
            String jiraKey = regexExtract(candidate);
            if (StringUtils.hasText(jiraKey)) {
                return jiraKey;
            }
        }

        for (JsonNode commit : commits) {
            String property = firstText(commit, "properties.jira-key", "commit.properties.jira-key", "properties['jira-key']");
            if (StringUtils.hasText(property)) {
                return property.trim();
            }
            String message = firstText(commit, "message", "commit.message", "commit.message.value");
            String jiraKey = regexExtract(message);
            if (StringUtils.hasText(jiraKey)) {
                return jiraKey;
            }
        }

        return null;
    }

    private String jiraKeySource(String sourceBranch, String title, String description, List<JsonNode> commits, String jiraKey) {
        if (!StringUtils.hasText(jiraKey)) {
            return "NONE";
        }

        if (StringUtils.hasText(regexExtract(sourceBranch)) && regexExtract(sourceBranch).equalsIgnoreCase(jiraKey)) {
            return "SOURCE_BRANCH";
        }
        if (StringUtils.hasText(regexExtract(title)) && regexExtract(title).equalsIgnoreCase(jiraKey)) {
            return "PR_TITLE";
        }
        if (StringUtils.hasText(regexExtract(description)) && regexExtract(description).equalsIgnoreCase(jiraKey)) {
            return "PR_DESCRIPTION";
        }

        for (JsonNode commit : commits) {
            String key = firstText(commit, "properties.jira-key", "commit.properties.jira-key", "properties['jira-key']");
            if (StringUtils.hasText(key) && key.trim().equalsIgnoreCase(jiraKey)) {
                return "COMMIT_PROPERTY";
            }
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

    private String activityActor(JsonNode activity) {
        return firstNonBlank(
                firstText(activity, "user.name"),
                firstText(activity, "user.slug"),
                firstText(activity, "author.name"),
                firstText(activity, "author.slug"),
                firstText(activity, "comment.author.name"),
                firstText(activity, "comment.author.slug"),
                firstText(activity, "participant.user.name"),
                firstText(activity, "participant.user.slug")
        );
    }

    private boolean sameUser(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private BitbucketPrAnalyticsQuery normalizeAnalyticsQuery(BitbucketPrAnalyticsQuery query) {
        int page = query == null ? 0 : Math.max(0, query.page());
        int size = query == null ? 25 : Math.max(1, Math.min(200, query.size()));
        return new BitbucketPrAnalyticsQuery(
                query == null ? null : query.projectId(),
                normalizeNullableText(query == null ? null : query.repository()),
                normalizeNullableText(query == null ? null : query.author()),
                normalizeNullableText(query == null ? null : query.state()),
                normalizeNullableText(query == null ? null : query.jira()),
                query == null ? null : query.createdFrom(),
                query == null ? null : query.createdTo(),
                query == null ? null : query.mergedFrom(),
                query == null ? null : query.mergedTo(),
                page,
                size,
                normalizeNullableText(query == null ? null : query.sort()),
                normalizeNullableText(query == null ? null : query.direction())
        );
    }

    private BitbucketPrAnalyticsSummary buildAnalyticsSummary(List<BitbucketPrRecord> records) {
        List<Double> cycleValues = records.stream()
                .map(BitbucketPrRecord::cycleTimeDays)
                .filter(value -> value != null && Double.isFinite(value))
                .sorted()
                .toList();
        double totalCycle = cycleValues.stream().mapToDouble(Double::doubleValue).sum();
        return new BitbucketPrAnalyticsSummary(
                records.size(),
                records.stream().filter(record -> "MERGED".equalsIgnoreCase(normalizeText(record.state()))).count(),
                records.stream().filter(record -> !"MERGED".equalsIgnoreCase(normalizeText(record.state()))).count(),
                records.stream().filter(this::isStaleOpenPullRequest).count(),
                cycleValues.isEmpty() ? null : totalCycle / cycleValues.size(),
                percentile(cycleValues, 0.50),
                percentile(cycleValues, 0.90)
        );
    }

    private BitbucketPrAnalyticsOptions buildAnalyticsOptions(List<BitbucketPrRecord> records) {
        return new BitbucketPrAnalyticsOptions(
                records.stream()
                        .map(BitbucketPrRecord::repoSlug)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .sorted()
                        .toList(),
                records.stream()
                        .map(this::displayAuthor)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .sorted()
                        .toList(),
                records.stream()
                        .map(BitbucketPrRecord::state)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .sorted()
                        .toList()
        );
    }

    private boolean isStaleOpenPullRequest(BitbucketPrRecord record) {
        if ("MERGED".equalsIgnoreCase(normalizeText(record.state()))) {
            return false;
        }
        OffsetDateTime start = record.cycleStart() != null ? record.cycleStart() : record.prCreatedAt();
        return start != null && start.plusDays(STALE_OPEN_DAYS).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private Double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private String displayAuthor(BitbucketPrRecord record) {
        return firstNonBlank(record.authorName(), record.authorUsername(), record.authorUserId(), "Unknown");
    }

    private String normalizeNullableText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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

    private static String firstNonBlank(String... values) {
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

    private record DiscoveryCandidate(
            BitbucketPrKey key,
            RepositorySummary repository,
            JsonNode summary,
            BitbucketUserMapping authorMapping
    ) {
        String label() {
            return key.projectKey() + "/" + key.repoSlug() + "#" + key.prId();
        }

        String bestAuthorFilter() {
            return firstNonBlank(authorMapping.preferredAuthorFilter(), firstTextValue(summary, "author.name", "author.slug"));
        }
    }

    private record UserPullRequestScanResult(List<DiscoveryCandidate> candidates) {
    }

    private record RepositorySummary(String projectKey, String repoSlug, String repoName) {
    }

    private record PrEnrichmentResult(BitbucketPrRecord record, SyncError error) {
    }

    private record UserResolutionResult(String sso, BitbucketUserMapping mapping, SyncError error) {
    }
}
