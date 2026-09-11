export interface ProjectOption {
  projectId: number;
  projectKey: string;
  projectName: string;
}

export interface ProjectSso {
  name: string;
  sso: string;
}

export interface BitbucketSyncRequest {
  projectId: number;
  fromDate: string;
  toDate: string;
  ssos: string[];
  fullRefresh?: boolean;
}

export interface SyncError {
  sso: string;
  error: string;
}

export interface BitbucketSyncResult {
  status: string;
  projectId: number;
  ssosRequested: number;
  userIdsResolved: number;
  repositoriesScanned: number;
  prsDiscovered: number;
  prsInserted: number;
  prsUpdated: number;
  errors: SyncError[];
  syncTime: string;
}

export interface BitbucketPrRecord {
  id: number;
  applicationProjectId: number;
  projectKey: string;
  projectName: string;
  repositoryName: string;
  repoSlug: string;
  prId: number;
  authorName: string;
  authorUsername: string;
  authorUserId: string;
  title: string;
  description: string;
  sourceBranch: string;
  destinationBranch: string;
  state: string;
  jiraKey: string;
  jiraMappingSource: string;
  prCreatedAt: string;
  firstCommitAt: string;
  firstReviewEngagementAt: string;
  prMergedAt: string;
  cycleStart: string;
  cycleStartSource: string;
  cycleTimeDays: number | null;
  lastSyncedAt: string;
}
