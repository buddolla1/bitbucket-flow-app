export interface ProjectOption {
  projectId: number;
  projectKey: string;
  projectName: string;
}

export interface ProjectSso {
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
  catalogStatus: string;
  projectsDiscovered: number;
  repositoriesDiscovered: number;
  repositoriesScanned: number;
  prsDiscovered: number;
  prsInserted: number;
  prsUpdated: number;
  errors: SyncError[];
  syncTime: string;
}

export interface BitbucketCatalogRefreshResult {
  status: string;
  projectsDiscovered: number;
  repositoriesDiscovered: number;
  refreshedAt: string;
}

