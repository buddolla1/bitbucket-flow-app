import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, BarChart3, Check, Database, GitPullRequest, Loader2, Plus, RefreshCw, Server } from 'lucide-react';
import {
  addProjectSso,
  fetchBitbucketPrRecords,
  fetchProjectSsos,
  fetchProjects,
  syncBitbucketData,
} from './services/bitbucketSyncApi';
import type { BitbucketPrRecord, BitbucketSyncResult, ProjectOption, ProjectSso } from './types/bitbucketSync';

type BusyState = 'idle' | 'loading' | 'saving-sso' | 'syncing' | 'loading-analytics';
type PageMode = 'sync' | 'analytics';

export function App() {
  const [pageMode, setPageMode] = useState<PageMode>('sync');
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [projectSsos, setProjectSsos] = useState<ProjectSso[]>([]);
  const [selectedSsos, setSelectedSsos] = useState<string[]>([]);
  const [prRecords, setPrRecords] = useState<BitbucketPrRecord[]>([]);
  const [repoFilter, setRepoFilter] = useState('');
  const [authorFilter, setAuthorFilter] = useState('');
  const [stateFilter, setStateFilter] = useState('');
  const [jiraFilter, setJiraFilter] = useState('');
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
  const analyticsRows = useMemo(
    () => filterPrRecords(prRecords, { repo: repoFilter, author: authorFilter, state: stateFilter, jira: jiraFilter }),
    [prRecords, repoFilter, authorFilter, stateFilter, jiraFilter]
  );
  const analyticsSummary = useMemo(() => buildAnalyticsSummary(analyticsRows), [analyticsRows]);
  const repoOptions = useMemo(() => uniqueSorted(prRecords.map((record) => record.repoSlug).filter(Boolean)), [prRecords]);
  const authorOptions = useMemo(() => uniqueSorted(prRecords.map((record) => displayAuthor(record)).filter(Boolean)), [prRecords]);
  const stateOptions = useMemo(() => uniqueSorted(prRecords.map((record) => record.state).filter(Boolean)), [prRecords]);

  useEffect(() => {
    void loadProjects();
  }, []);

  useEffect(() => {
    if (!selectedProjectId) {
      setProjectSsos([]);
      setSelectedSsos([]);
      setPrRecords([]);
      return;
    }
    void loadSsos(Number(selectedProjectId));
    if (pageMode === 'analytics') {
      void loadAnalytics(Number(selectedProjectId));
    }
  }, [selectedProjectId]);

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
    await runAction('loading-analytics', () => fetchBitbucketPrRecords(projectId), (records) => {
      setPrRecords(records);
      setMessage(records.length ? `${records.length} synced PR records loaded.` : 'No synced PR records found for this project.');
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
    if (selectedProject) {
      void loadAnalytics(selectedProject.projectId);
    }
  }

  function clearAnalyticsFilters() {
    setRepoFilter('');
    setAuthorFilter('');
    setStateFilter('');
    setJiraFilter('');
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
                  <select value={repoFilter} onChange={(event) => setRepoFilter(event.target.value)}>
                    <option value="">All repositories</option>
                    {repoOptions.map((repo) => <option key={repo} value={repo}>{repo}</option>)}
                  </select>
                </label>
                <label>
                  <span>Author</span>
                  <select value={authorFilter} onChange={(event) => setAuthorFilter(event.target.value)}>
                    <option value="">All authors</option>
                    {authorOptions.map((author) => <option key={author} value={author}>{author}</option>)}
                  </select>
                </label>
                <label>
                  <span>State</span>
                  <select value={stateFilter} onChange={(event) => setStateFilter(event.target.value)}>
                    <option value="">All states</option>
                    {stateOptions.map((state) => <option key={state} value={state}>{state}</option>)}
                  </select>
                </label>
                <label>
                  <span>Jira key</span>
                  <input value={jiraFilter} onChange={(event) => setJiraFilter(event.target.value)} placeholder="ABC-123" />
                </label>
                <button type="button" className="secondary" onClick={clearAnalyticsFilters}>
                  Clear Filters
                </button>
              </div>
            </div>

            <section className="kpi-grid">
              <KpiCard label="PRs" value={String(analyticsSummary.total)} />
              <KpiCard label="Merged" value={String(analyticsSummary.merged)} />
              <KpiCard label="Open" value={String(analyticsSummary.open)} />
              <KpiCard label="Avg cycle" value={formatDays(analyticsSummary.averageCycleDays)} />
            </section>

            <section className="panel panel--wide">
              <div className="panel-header">
                <div>
                  <div className="panel-title">Synced Records</div>
                  <p className="panel-subtitle">{analyticsRows.length} of {prRecords.length} records shown.</p>
                </div>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>PR</th>
                      <th>Repository</th>
                      <th>Author</th>
                      <th>State</th>
                      <th>Jira</th>
                      <th>Created</th>
                      <th>First commit</th>
                      <th>First review</th>
                      <th>Merged</th>
                      <th>Cycle days</th>
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
            </section>
          </section>
        )}

        {syncResult?.errors.length ? (
          <section className="panel">
            <div className="panel-title">Sync Errors</div>
            <div className="error-list">
              {syncResult.errors.map((item, index) => (
                <div key={`${item.sso}-${index}`}>
                  <AlertTriangle size={16} />
                  <span>{item.sso}: {item.error}</span>
                </div>
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

function filterPrRecords(
  records: BitbucketPrRecord[],
  filters: { repo: string; author: string; state: string; jira: string }
) {
  const jiraSearch = filters.jira.trim().toLowerCase();
  return records.filter((record) => {
    if (filters.repo && record.repoSlug !== filters.repo) {
      return false;
    }
    if (filters.author && displayAuthor(record) !== filters.author) {
      return false;
    }
    if (filters.state && record.state !== filters.state) {
      return false;
    }
    if (jiraSearch && !(record.jiraKey ?? '').toLowerCase().includes(jiraSearch)) {
      return false;
    }
    return true;
  });
}

function buildAnalyticsSummary(records: BitbucketPrRecord[]) {
  const cycleValues = records
    .map((record) => record.cycleTimeDays)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  const totalCycle = cycleValues.reduce((sum, value) => sum + value, 0);
  return {
    total: records.length,
    merged: records.filter((record) => (record.state ?? '').toUpperCase() === 'MERGED').length,
    open: records.filter((record) => (record.state ?? '').toUpperCase() !== 'MERGED').length,
    averageCycleDays: cycleValues.length ? totalCycle / cycleValues.length : null,
  };
}

function uniqueSorted(values: string[]) {
  return [...new Set(values.filter(Boolean))].sort((left, right) => left.localeCompare(right));
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
