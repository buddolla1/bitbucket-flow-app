import { useEffect, useState } from 'react';
import { AlertTriangle, Check, Database, GitPullRequest, Loader2, Plus, RefreshCw, Server } from 'lucide-react';
import {
  addProjectSso,
  createProject,
  fetchProjectSsos,
  fetchProjects,
  refreshBitbucketCatalog,
  syncBitbucketData,
} from './services/bitbucketSyncApi';
import type { BitbucketCatalogRefreshResult, BitbucketSyncResult, ProjectOption } from './types/bitbucketSync';

type BusyState = 'idle' | 'loading' | 'saving-project' | 'saving-sso' | 'refreshing' | 'syncing';

export function App() {
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [projectSsos, setProjectSsos] = useState<string[]>([]);
  const [selectedSsos, setSelectedSsos] = useState<string[]>([]);
  const [projectKey, setProjectKey] = useState('');
  const [projectName, setProjectName] = useState('');
  const [newSso, setNewSso] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [fullRefresh, setFullRefresh] = useState(false);
  const [busyState, setBusyState] = useState<BusyState>('idle');
  const [message, setMessage] = useState('Connect MySQL and Bitbucket, then set up a project.');
  const [error, setError] = useState('');
  const [catalogResult, setCatalogResult] = useState<BitbucketCatalogRefreshResult | null>(null);
  const [syncResult, setSyncResult] = useState<BitbucketSyncResult | null>(null);

  const selectedProject = projects.find((project) => String(project.projectId) === selectedProjectId) ?? null;
  const isBusy = busyState !== 'idle';
  const canSync = Boolean(selectedProject) && selectedSsos.length > 0 && !isBusy;

  useEffect(() => {
    void loadProjects();
  }, []);

  useEffect(() => {
    if (!selectedProjectId) {
      setProjectSsos([]);
      setSelectedSsos([]);
      return;
    }
    void loadSsos(Number(selectedProjectId));
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
      setMessage(nextProjects.length ? 'Choose a project and SSOs to sync.' : 'Create a project to begin.');
      if (!selectedProjectId && nextProjects.length === 1) {
        setSelectedProjectId(String(nextProjects[0].projectId));
      }
    });
  }

  async function loadSsos(projectId: number) {
    await runAction('loading', () => fetchProjectSsos(projectId), (nextSsos) => {
      const values = nextSsos.map((item) => item.sso).filter(Boolean);
      setProjectSsos(values);
      setSelectedSsos([]);
      setMessage(values.length ? `${values.length} SSOs loaded.` : 'Add SSOs for this project.');
    });
  }

  async function handleCreateProject() {
    if (!projectKey.trim() || !projectName.trim()) {
      setError('Project key and project name are required.');
      return;
    }
    await runAction('saving-project', () => createProject(projectKey, projectName), (project) => {
      setProjects((current) => {
        const filtered = current.filter((item) => item.projectId !== project.projectId);
        return [...filtered, project].sort((left, right) => left.projectName.localeCompare(right.projectName));
      });
      setSelectedProjectId(String(project.projectId));
      setProjectKey('');
      setProjectName('');
      setMessage(`Project ${project.projectKey} is ready.`);
    });
  }

  async function handleAddSso() {
    if (!selectedProject || !newSso.trim()) {
      setError('Select a project and enter an SSO.');
      return;
    }
    await runAction('saving-sso', () => addProjectSso(selectedProject.projectId, newSso), (nextSsos) => {
      const values = nextSsos.map((item) => item.sso).filter(Boolean);
      setProjectSsos(values);
      setSelectedSsos(values);
      setNewSso('');
      setMessage('SSO list updated.');
    });
  }

  async function handleRefreshCatalog() {
    await runAction('refreshing', refreshBitbucketCatalog, (result) => {
      setCatalogResult(result);
      setMessage(`Catalog refreshed: ${result.projectsDiscovered} projects and ${result.repositoriesDiscovered} repositories.`);
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
          fullRefresh,
        }),
      (result) => {
        setSyncResult(result);
        setMessage(`Sync complete: ${result.prsDiscovered} PRs found, ${result.prsInserted} inserted, ${result.prsUpdated} updated.`);
      }
    );
  }

  function toggleSso(sso: string) {
    setSelectedSsos((current) => (current.includes(sso) ? current.filter((item) => item !== sso) : [...current, sso]));
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

        <section className="grid">
          <div className="panel">
            <div className="panel-title">Project Setup</div>
            <label>
              <span>Project key</span>
              <input value={projectKey} onChange={(event) => setProjectKey(event.target.value)} placeholder="APP" />
            </label>
            <label>
              <span>Project name</span>
              <input value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="Application Platform" />
            </label>
            <button type="button" onClick={handleCreateProject} disabled={isBusy}>
              <Plus size={16} />
              Save Project
            </button>
          </div>

          <div className="panel">
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
                <button type="button" className="secondary" onClick={() => setSelectedSsos(projectSsos)} disabled={isBusy || !projectSsos.length}>
                  Select All
                </button>
                <button type="button" className="secondary" onClick={() => setSelectedSsos([])} disabled={isBusy || !selectedSsos.length}>
                  Clear
                </button>
              </div>
            </div>
            <div className="sso-list">
              {projectSsos.length ? (
                projectSsos.map((sso) => (
                  <label key={sso} className="sso-chip">
                    <input type="checkbox" checked={selectedSsos.includes(sso)} onChange={() => toggleSso(sso)} />
                    <span>{sso}</span>
                  </label>
                ))
              ) : (
                <div className="empty-state">No SSOs available for this project.</div>
              )}
            </div>
          </div>

          <div className="panel panel--wide">
            <div className="panel-title">Run Sync</div>
            <div className="sync-controls">
              <label>
                <span>From date</span>
                <input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
              </label>
              <label>
                <span>To date</span>
                <input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} />
              </label>
              <label className="toggle">
                <input type="checkbox" checked={fullRefresh} onChange={(event) => setFullRefresh(event.target.checked)} />
                <span>Full refresh</span>
              </label>
            </div>
            <div className="button-row">
              <button type="button" className="secondary" onClick={handleRefreshCatalog} disabled={isBusy}>
                <RefreshCw size={16} />
                Refresh Catalog
              </button>
              <button type="button" onClick={handleSync} disabled={!canSync}>
                <GitPullRequest size={16} />
                Sync Pull Requests
              </button>
            </div>
          </div>
        </section>

        <section className="result-grid">
          <ResultCard title="Catalog" rows={catalogResult ? [
            ['Status', catalogResult.status],
            ['Projects', String(catalogResult.projectsDiscovered)],
            ['Repositories', String(catalogResult.repositoriesDiscovered)],
            ['Refreshed', catalogResult.refreshedAt],
          ] : []} />
          <ResultCard title="Latest Sync" rows={syncResult ? [
            ['Status', syncResult.status],
            ['Users resolved', String(syncResult.userIdsResolved)],
            ['Repositories scanned', String(syncResult.repositoriesScanned)],
            ['PRs discovered', String(syncResult.prsDiscovered)],
            ['Inserted', String(syncResult.prsInserted)],
            ['Updated', String(syncResult.prsUpdated)],
          ] : []} />
        </section>

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

