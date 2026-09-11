package com.jira.analytics.controller;

import com.jira.analytics.dto.BitbucketSyncRequest;
import com.jira.analytics.dto.BitbucketSyncResult;
import com.jira.analytics.dto.AddProjectSsoRequest;
import com.jira.analytics.dto.BitbucketPrAnalyticsPage;
import com.jira.analytics.dto.BitbucketPrAnalyticsQuery;
import com.jira.analytics.dto.ProjectOption;
import com.jira.analytics.dto.ProjectSso;
import com.jira.analytics.service.BitbucketSyncService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BitbucketSyncController {

    private static final Logger log = LoggerFactory.getLogger(BitbucketSyncController.class);

    private final BitbucketSyncService bitbucketSyncService;

    public BitbucketSyncController(BitbucketSyncService bitbucketSyncService) {
        this.bitbucketSyncService = bitbucketSyncService;
    }

    @GetMapping("/projects")
    public List<ProjectOption> projects() {
        log.info("Fetching application projects");
        return bitbucketSyncService.findProjects();
    }

    @GetMapping("/projects/{projectId}/ssos")
    public List<ProjectSso> projectSsos(@PathVariable Long projectId) {
        log.info("Fetching SSOs for projectId={}", projectId);
        return bitbucketSyncService.findProjectSsos(projectId);
    }

    @PostMapping("/projects/{projectId}/ssos")
    public List<ProjectSso> addProjectSso(@PathVariable Long projectId, @RequestBody AddProjectSsoRequest request) {
        log.info("Adding SSO for projectId={}", projectId);
        bitbucketSyncService.addProjectSso(projectId, request);
        return projectSsos(projectId);
    }

    @PostMapping("/bitbucket/sync")
    public BitbucketSyncResult sync(@RequestBody(required = false) BitbucketSyncRequest request) {
        log.info(
                "Starting Bitbucket sync request projectId={} ssoCount={} fromDate={} toDate={}",
                request == null ? null : request.projectId(),
                request == null || request.ssos() == null ? 0 : request.ssos().size(),
                request == null ? null : request.fromDate(),
                request == null ? null : request.toDate()
        );
        return bitbucketSyncService.sync(request);
    }

    @GetMapping("/bitbucket/pull-requests")
    public BitbucketPrAnalyticsPage pullRequests(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String jira,
            @RequestParam(required = false) LocalDate createdFrom,
            @RequestParam(required = false) LocalDate createdTo,
            @RequestParam(required = false) LocalDate mergedFrom,
            @RequestParam(required = false) LocalDate mergedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "activity") String sort,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        log.info(
                "Fetching Bitbucket analytics projectId={} repository={} author={} state={} page={} size={} sort={} direction={}",
                projectId,
                repository,
                author,
                state,
                page,
                size,
                sort,
                direction
        );
        return bitbucketSyncService.findPullRequestAnalytics(new BitbucketPrAnalyticsQuery(
                projectId,
                repository,
                author,
                state,
                jira,
                createdFrom,
                createdTo,
                mergedFrom,
                mergedTo,
                page,
                size,
                sort,
                direction
        ));
    }
}
