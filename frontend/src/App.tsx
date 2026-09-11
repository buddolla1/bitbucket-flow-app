import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, BarChart3, Check, ChevronLeft, ChevronRight, Database, Download, GitPullRequest, Loader2, Plus, RefreshCw, Server } from 'lucide-react';
import {
  addProjectSso,
  fetchBitbucketPrRecords,
  fetchProjectSsos,
  fetchProjects,
  syncBitbucketData,
} from './services/bitbucketSyncApi';
import type { BitbucketPrAnalyticsPage, BitbucketPrRecord, BitbucketSyncResult, ProjectOption, ProjectSso, SyncError } from './types/bitbucketSync';

type BusyState = 'idle' | 'loading' | 'saving-sso' | 'syncing' | 'loading-analytics';
type PageMode = 'sync' | 'analytics';

export function App() {
  const [pageMode, setPageMode] = useState<PageMode>('sync');
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [projectSsos, setProjectSsos] = useState<ProjectSso[]>([]);
  const [selectedSsos, setSelectedSsos] = useState<string[]>([]);
  const [analyticsPage, setAnalyticsPage] = useState<BitbucketPrAnalyticsPage | null>(null);
  const [repoFilter, setRepoFilter] = useState('');
  const [authorFilter, setAuthorFilter] = useState('');
  const [stateFilter, setStateFilter] = useState('');
  const [jiraFilter, setJiraFilter] = useState('');
  const [createdFromFilter, setCreatedFromFilter] = useState('');
  const [createdToFilter, setCreatedToFilter] = useState('');
  const [mergedFromFilter, setMergedFromFilter] = useState('');
  const [mergedToFilter, setMergedToFilter] = useState('');
  const [analyticsPageIndex, setAnalyticsPageIndex] = useState(0);
  const [analyticsPageSize, setAnalyticsPageSize] = useState(25);
  const [analyticsSort, setAnalyticsSort] = useState('activity');
  const [analyticsDirection, setAnalyticsDirection] = useState<'asc' | 'desc'>('desc');
  const [newSso, setNewSso] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [busyState, setBusyState] = useState<BusyState>('idle');
  const [message, setMessage] = useState('Connect MySQL and Bitbucket, then set up a project.');
  const [error, setError] = useState('');
  const [syncResult, setSyncResult] = useState<BitbucketSyncResult | null>(null);

  const selectedProject = projects.find((project) => String(project.projectId) === selectedProjectId) ?? null;
  const isBusy = busyState !== 'idle';
  const canSync = Boolean(selectedProject) && selectedSsos.length > 0 && !isBusy;
  const analyticsRows = analyticsPage?.records ?? [];
  const analyticsSummary = analyticsPage?.summary ?? null;
  const repoOptions = analyticsPage?.options.repositories ?? [];
  const authorOptions = analyticsPage?.options.authors ?? [];
  const stateOptions = analyticsPage?.options.states ?? [];
  const groupedErrors = useMemo(() => groupSyncErrors(syncResult?.errors ?? []), [syncResult]);

  useEffect(() => {
    void loadProjects();
  }, []);

  useEffect(() => {
    if (!selectedProjectId) {
      setProjectSsos([]);
      setSelectedSsos([]);
      setAnalyticsPage(null);
      return;
    }
    void loadSsos(Number(selectedProjectId));
    setAnalyticsPageIndex(0);
  }, [selectedProjectId]);

  useEffect(() => {
    if (pageMode === 'analytics' && selectedProject) {
      void loadAnalytics(selectedProject.projectId);
    }
  }, [
    pageMode,
    selectedProjectId,
    repoFilter,
    authorFilter,
    stateFilter,
    jiraFilter,
    createdFromFilter,
    createdToFilter,
    mergedFromFilter,
    mergedToFilter,
    analyticsPageIndex,
    analyticsPageSize,
    analyticsSort,
    analyticsDirection,
  ]);

  async function runAction<T>(state: BusyState, action: () => Promise<T>, success: (result: T) => void) {
    setBusyState(state);
    setError('');
    try {
      const result = await action();
      success(result);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Request failed.');
    } finally {
      setBusyState('idle');
    }
  }

  async function loadProjects() {
    await runAction('loading', fetchProjects, (nextProjects) => {
      setProjects(nextProjects);
      setMessage(nextProjects.length ? 'Choose a project and SSOs to sync.' : 'No Jira projects found yet.');
      if (!selectedProjectId && nextProjects.length === 1) {
        setSelectedProjectId(String(nextProjects[0].projectId));
      }
    });
  }

  async function loadSsos(projectId: number) {
    await runAction('loading', () => fetchProjectSsos(projectId), (nextSsos) => {
      const values = nextSsos.filter((item) => item.sso);
      setProjectSsos(values);
      setSelectedSsos([]);
      setMessage(values.length ? `${values.length} SSOs loaded.` : 'Add SSOs for this project.');
    });
  }

  async function loadAnalytics(projectId = selectedProject ? selectedProject.projectId : undefined) {
    if (!projectId) {
      setError('Select a project before loading analytics.');
      return;
    }
    await runAction('loading-analytics', () => fetchBitbucketPrRecords({
      projectId,
      repository: repoFilter,
      author: authorFilter,
      state: stateFilter,
      jira: jiraFilter,
      createdFrom: createdFromFilter,
      createdTo: createdToFilter,
      mergedFrom: mergedFromFilter,
      mergedTo: mergedToFilter,
      page: analyticsPageIndex,
      size: analyticsPageSize,
      sort: analyticsSort,
      direction: analyticsDirection,
    }), (page) => {
      setAnalyticsPage(page);
      setMessage(page.totalRecords ? `${page.totalRecords} synced PR records match the filters.` : 'No synced PR records found for this project.');
    });
  }

  async function handleAddSso() {
    if (!selectedProject || !newSso.trim()) {
      setError('Select a project and enter an SSO.');
      return;
    }
    await runAction('saving-sso', () => addProjectSso(selectedProject.projectId, newSso), (nextSsos) => {
      const values = nextSsos.filter((item) => item.sso);
      setProjectSsos(values);
      setSelectedSsos(values.map((item) => item.sso));
      setNewSso('');
      setMessage('SSO list updated.');
    });
  }

  async function handleSync() {
    if (!selectedProject || !canSync) {
      return;
    }
    await runAction(
      'syncing',
      () =>
        syncBitbucketData({
          projectId: selectedProject.projectId,
          fromDate: fromDate.trim(),
          toDate: toDate.trim(),
          ssos: selectedSsos,
        }),
      (result) => {
        setSyncResult(result);
        setMessage(`Sync complete: ${result.prsDiscovered} PRs found, ${result.prsInserted} inserted, ${result.prsUpdated} updated.`);
        void loadAnalytics(selectedProject.projectId);
      }
    );
  }

  function toggleSso(sso: string) {
    setSelectedSsos((current) => (current.includes(sso) ? current.filter((item) => item !== sso) : [...current, sso]));
  }

  function showAnalytics() {
    setPageMode('analytics');
  }

  function clearAnalyticsFilters() {
    setRepoFilter('');
    setAuthorFilter('');
    setStateFilter('');
    setJiraFilter('');
    setCreatedFromFilter('');
    setCreatedToFilter('');
    setMergedFromFilter('');
    setMergedToFilter('');
    setAnalyticsPageIndex(0);
  }

  function updateAnalyticsFilter(setter: (value: string) => void, value: string) {
    setter(value);
    setAnalyticsPageIndex(0);
  }

  function handleSortChange(sort: string) {
    if (sort === analyticsSort) {
      setAnalyticsDirection((current) => (current === 'asc' ? 'desc' : 'asc'));
    } else {
      setAnalyticsSort(sort);
      setAnalyticsDirection('desc');
    }
    setAnalyticsPageIndex(0);
  }

  function exportAnalyticsCsv() {
    if (!analyticsRows.length) {
      return;
    }
    const csv = toCsv(analyticsRows);
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `bitbucket-pr-analytics-page-${(analyticsPage?.page ?? 0) + 1}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="app-shell">
      <section className="workspace">
        <header className="app-header">
          <div>
            <p className="eyebrow">Bitbucket Flow</p>
            <h1>Pull request sync console</h1>
          </div>
          <div className="status-pill">
            {isBusy ? <Loader2 size={16} className="spin" /> : <Server size={16} />}
            <span>{busyState === 'idle' ? 'Ready' : busyState.replace('-', ' ')}</span>
          </div>
        </header>

        <div className="notice-row">
          <div className="notice">
            <Database size={18} />
            <span>{message}</span>
          </div>
          {error ? (
            <div className="notice notice--error">
              <AlertTriangle size={18} />
              <span>{error}</span>
            </div>
          ) : null}
        </div>

        <div className="mode-tabs">
          <button type="button" className={pageMode === 'sync' ? 'mode-tab mode-tab--active' : 'mode-tab'} onClick={() => setPageMode('sync')}>
            <GitPullRequest size={16} />
            Sync
          </button>
          <button type="button" className={pageMode === 'analytics' ? 'mode-tab mode-tab--active' : 'mode-tab'} onClick={showAnalytics}>
            <BarChart3 size={16} />
            Analytics
          </button>
        </div>

        {pageMode === 'sync' ? (
        <>
          <section className="grid">
          <div className="panel panel--wide">
            <div className="panel-title">Sync Target</div>
            <label>
              <span>Application project</span>
              <select value={selectedProjectId} onChange={(event) => setSelectedProjectId(event.target.value)} disabled={isBusy}>
                <option value="">Select project</option>
                {projects.map((project) => (
                  <option key={project.projectId} value={project.projectId}>
                    {project.projectName} ({project.projectKey})
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Add SSO</span>
              <div className="inline-field">
                <input value={newSso} onChange={(event) => setNewSso(event.target.value)} placeholder="user.sso" />
                <button type="button" className="icon-button" onClick={handleAddSso} disabled={isBusy || !selectedProject}>
                  <Plus size={16} />
                </button>
              </div>
            </label>
          </div>

          <div className="panel panel--wide">
            <div className="panel-header">
              <div className="panel-title">SSOs</div>
              <div className="button-row">
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setSelectedSsos(projectSsos.map((item) => item.sso))}
                  disabled={isBusy || !projectSsos.length}
                >
                  Select All
                </button>
                <button type="button" className="secondary" onClick={() => setSelectedSsos([])} disabled={isBusy || !selectedSsos.length}>
                  Clear
                </button>
              </div>
            </div>
            <div className="sso-list">
              {projectSsos.length ? (
                projectSsos.map((item) => (
                  <label key={item.sso} className="sso-chip">
                    <input type="checkbox" checked={selectedSsos.includes(item.sso)} onChange={() => toggleSso(item.sso)} />
                    <span className="sso-chip__text">
                      <strong>{item.name || item.sso}</strong>
                      <small>{item.sso}</small>
                    </span>
                  </label>
                ))
              ) : (
                <div className="empty-state">No SSOs available for this project.</div>
              )}
            </div>
          </div>

          <div className="panel panel--wide">
            <div className="panel-title">Run Sync</div>
            <div className="sync-controls sync-controls--dates">
              <label>
                <span>From date</span>
                <input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
              </label>
              <label>
                <span>To date</span>
                <input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} />
              </label>
            </div>
            <div className="button-row">
              <button type="button" onClick={handleSync} disabled={!canSync}>
                <GitPullRequest size={16} />
                Sync Pull Requests
              </button>
            </div>
          </div>
        </section>

        <section className="result-grid">
          <ResultCard title="Latest Sync" rows={syncResult ? [
            ['Status', syncResult.status],
            ['Users resolved', String(syncResult.userIdsResolved)],
            ['Repositories scanned', String(syncResult.repositoriesScanned)],
            ['PRs discovered', String(syncResult.prsDiscovered)],
            ['Inserted', String(syncResult.prsInserted)],
            ['Updated', String(syncResult.prsUpdated)],
          ] : []} />
        </section>
        </>
        ) : (
          <section className="analytics-stack">
            <div className="panel panel--wide">
              <div className="panel-header">
                <div>
                  <div className="panel-title">Synced PR Analytics</div>
                  <p className="panel-subtitle">Read-only view of records stored in the database for the selected application project.</p>
                </div>
                <button type="button" className="secondary" onClick={() => void loadAnalytics()} disabled={isBusy || !selectedProject}>
                  {busyState === 'loading-analytics' ? <Loader2 size={16} className="spin" /> : <RefreshCw size={16} />}
                  Refresh
                </button>
              </div>

              <div className="analytics-filters">
                <label>
                  <span>Repository</span>
                  <select value={repoFilter} onChange={(event) => updateAnalyticsFilter(setRepoFilter, event.target.value)}>
                    <option value="">All repositories</option>
                    {repoOptions.map((repo) => <option key={repo} value={repo}>{repo}</option>)}
                  </select>
                </label>
                <label>
                  <span>Author</span>
                  <select value={authorFilter} onChange={(event) => updateAnalyticsFilter(setAuthorFilter, event.target.value)}>
                    <option value="">All authors</option>
                    {authorOptions.map((author) => <option key={author} value={author}>{author}</option>)}
                  </select>
                </label>
                <label>
                  <span>State</span>
                  <select value={stateFilter} onChange={(event) => updateAnalyticsFilter(setStateFilter, event.target.value)}>
                    <option value="">All states</option>
                    {stateOptions.map((state) => <option key={state} value={state}>{state}</option>)}
                  </select>
                </label>
                <label>
                  <span>Jira key</span>
                  <input value={jiraFilter} onChange={(event) => updateAnalyticsFilter(setJiraFilter, event.target.value)} placeholder="ABC-123" />
                </label>
                <label>
                  <span>Created from</span>
                  <input type="date" value={createdFromFilter} onChange={(event) => updateAnalyticsFilter(setCreatedFromFilter, event.target.value)} />
                </label>
                <label>
                  <span>Created to</span>
                  <input type="date" value={createdToFilter} onChange={(event) => updateAnalyticsFilter(setCreatedToFilter, event.target.value)} />
                </label>
                <label>
                  <span>Merged from</span>
                  <input type="date" value={mergedFromFilter} onChange={(event) => updateAnalyticsFilter(setMergedFromFilter, event.target.value)} />
                </label>
                <label>
                  <span>Merged to</span>
                  <input type="date" value={mergedToFilter} onChange={(event) => updateAnalyticsFilter(setMergedToFilter, event.target.value)} />
                </label>
                <button type="button" className="secondary" onClick={clearAnalyticsFilters}>
                  Clear Filters
                </button>
              </div>
            </div>

            <section className="kpi-grid">
              <KpiCard label="PRs" value={String(analyticsSummary?.total ?? 0)} />
              <KpiCard label="Merged" value={String(analyticsSummary?.merged ?? 0)} />
              <KpiCard label="Open" value={String(analyticsSummary?.open ?? 0)} />
              <KpiCard label="Stale open" value={String(analyticsSummary?.staleOpen ?? 0)} />
              <KpiCard label="Avg cycle" value={formatDays(analyticsSummary?.averageCycleDays)} />
              <KpiCard label="Median cycle" value={formatDays(analyticsSummary?.medianCycleDays)} />
              <KpiCard label="P90 cycle" value={formatDays(analyticsSummary?.p90CycleDays)} />
            </section>

            <section className="panel panel--wide">
              <div className="panel-header">
                <div>
                  <div className="panel-title">Synced Records</div>
                  <p className="panel-subtitle">
                    {analyticsRows.length} of {analyticsPage?.totalRecords ?? 0} records shown.
                  </p>
                </div>
                <div className="button-row">
                  <label className="compact-field">
                    <span>Rows</span>
                    <select value={analyticsPageSize} onChange={(event) => {
                      setAnalyticsPageSize(Number(event.target.value));
                      setAnalyticsPageIndex(0);
                    }}>
                      <option value={25}>25</option>
                      <option value={50}>50</option>
                      <option value={100}>100</option>
                      <option value={200}>200</option>
                    </select>
                  </label>
                  <button type="button" className="secondary" onClick={exportAnalyticsCsv} disabled={!analyticsRows.length}>
                    <Download size={16} />
                    Export CSV
                  </button>
                </div>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>PR</th>
                      <SortableTh label="Repository" sortKey="repo" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <SortableTh label="Author" sortKey="author" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <SortableTh label="State" sortKey="state" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <SortableTh label="Jira" sortKey="jira" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <SortableTh label="Created" sortKey="created" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <th>First commit</th>
                      <th>First review</th>
                      <SortableTh label="Merged" sortKey="merged" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                      <SortableTh label="Cycle days" sortKey="cycle" activeSort={analyticsSort} direction={analyticsDirection} onSort={handleSortChange} />
                    </tr>
                  </thead>
                  <tbody>
                    {analyticsRows.length ? analyticsRows.map((record) => (
                      <tr key={`${record.projectKey}-${record.repoSlug}-${record.prId}`}>
                        <td>
                          <div className="table-primary">#{record.prId}</div>
                          <div className="table-secondary">{record.title || 'Untitled PR'}</div>
                        </td>
                        <td>{record.repoSlug}</td>
                        <td>{displayAuthor(record)}</td>
                        <td>{record.state || 'Unknown'}</td>
                        <td>{record.jiraKey || '-'}</td>
                        <td>{formatDate(record.prCreatedAt)}</td>
                        <td>{formatDate(record.firstCommitAt)}</td>
                        <td>{formatDate(record.firstReviewEngagementAt)}</td>
                        <td>{formatDate(record.prMergedAt)}</td>
                        <td>{formatDays(record.cycleTimeDays)}</td>
                      </tr>
                    )) : (
                      <tr>
                        <td colSpan={10}>No synced PR records match the selected filters.</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              <div className="pagination-bar">
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setAnalyticsPageIndex((current) => Math.max(0, current - 1))}
                  disabled={isBusy || analyticsPageIndex === 0}
                >
                  <ChevronLeft size={16} />
                  Previous
                </button>
                <span>
                  Page {(analyticsPage?.page ?? analyticsPageIndex) + 1} of {Math.max(analyticsPage?.totalPages ?? 0, 1)}
                </span>
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setAnalyticsPageIndex((current) => current + 1)}
                  disabled={isBusy || !analyticsPage || analyticsPage.page + 1 >= analyticsPage.totalPages}
                >
                  Next
                  <ChevronRight size={16} />
                </button>
              </div>
            </section>
          </section>
        )}

        {syncResult?.errors.length ? (
          <section className="panel">
            <div className="panel-title">Sync Errors</div>
            <div className="error-list">
              {Object.entries(groupedErrors).map(([phase, items]) => (
                <section key={phase} className="error-group">
                  <div className="error-group__title">{phase}</div>
                  {items.map((item, index) => (
                    <div key={`${item.sso}-${index}`}>
                      <AlertTriangle size={16} />
                      <span>{item.sso}: {item.error}</span>
                    </div>
                  ))}
                </section>
              ))}
            </div>
          </section>
        ) : null}
      </section>
    </main>
  );
}

function ResultCard({ title, rows }: { title: string; rows: [string, string][] }) {
  return (
    <div className="panel">
      <div className="panel-title">{title}</div>
      {rows.length ? (
        <dl className="metric-list">
          {rows.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <div className="empty-state">
          <Check size={18} />
          <span>No result yet.</span>
        </div>
      )}
    </div>
  );
}

function KpiCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="kpi-card">
      <div className="kpi-card__title">{label}</div>
      <div className="kpi-card__value">{value}</div>
    </div>
  );
}

function SortableTh({
  label,
  sortKey,
  activeSort,
  direction,
  onSort,
}: {
  label: string;
  sortKey: string;
  activeSort: string;
  direction: 'asc' | 'desc';
  onSort: (sort: string) => void;
}) {
  const active = activeSort === sortKey;
  return (
    <th>
      <button type="button" className="sort-button" onClick={() => onSort(sortKey)}>
        {label}
        <span>{active ? (direction === 'asc' ? 'up' : 'down') : ''}</span>
      </button>
    </th>
  );
}

function displayAuthor(record: BitbucketPrRecord) {
  return record.authorName || record.authorUsername || record.authorUserId || 'Unknown';
}

function formatDate(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(date);
}

function formatDays(value: number | null | undefined) {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return `${value.toFixed(1)}d`;
}

function groupSyncErrors(errors: SyncError[]) {
  return errors.reduce<Record<string, SyncError[]>>((groups, item) => {
    const phase = errorPhase(item.sso);
    groups[phase] = [...(groups[phase] ?? []), item];
    return groups;
  }, {});
}

function errorPhase(source: string) {
  if (source === 'USER_RESOLUTION') {
    return 'User resolution';
  }
  if (source === 'USER_PR_SCAN') {
    return 'Pull request discovery';
  }
  if (source.includes('/')) {
    return 'Pull request enrichment';
  }
  return 'User mapping';
}

function toCsv(records: BitbucketPrRecord[]) {
  const headers = [
    'PR',
    'Repository',
    'Author',
    'State',
    'Jira',
    'Created',
    'First commit',
    'First review',
    'Merged',
    'Cycle days',
    'Title',
  ];
  const rows = records.map((record) => [
    record.prId,
    record.repoSlug,
    displayAuthor(record),
    record.state,
    record.jiraKey,
    record.prCreatedAt,
    record.firstCommitAt,
    record.firstReviewEngagementAt,
    record.prMergedAt,
    record.cycleTimeDays ?? '',
    record.title,
  ]);
  return [headers, ...rows].map((row) => row.map(csvCell).join(',')).join('\n');
}

function csvCell(value: unknown) {
  const text = String(value ?? '');
  return `"${text.replaceAll('"', '""')}"`;
}
