import type {
  BitbucketPrRecord,
  BitbucketSyncRequest,
  BitbucketSyncResult,
  ProjectOption,
  ProjectSso,
} from '../types/bitbucketSync';

async function readErrorMessage(response: Response): Promise<string> {
  const contentType = response.headers.get('content-type') ?? '';

  if (contentType.includes('application/json')) {
    try {
      const payload: unknown = await response.json();
      if (payload && typeof payload === 'object') {
        const record = payload as { message?: unknown; error?: unknown; detail?: unknown };
        const message = record.message ?? record.error ?? record.detail;
        if (typeof message === 'string' && message.trim()) {
          return message;
        }
      }
    } catch {
      return `HTTP ${response.status}`;
    }
  }

  const text = (await response.text()).trim();
  return text || `HTTP ${response.status}`;
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(await readErrorMessage(response));
  }
  return (await response.json()) as T;
}

export async function fetchProjects(): Promise<ProjectOption[]> {
  return await readJson<ProjectOption[]>(await fetch('/api/projects'));
}

export async function fetchProjectSsos(projectId: number): Promise<ProjectSso[]> {
  return await readJson<ProjectSso[]>(await fetch(`/api/projects/${projectId}/ssos`));
}

export async function addProjectSso(projectId: number, sso: string): Promise<ProjectSso[]> {
  return await readJson<ProjectSso[]>(
    await fetch(`/api/projects/${projectId}/ssos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sso }),
    })
  );
}

export async function syncBitbucketData(payload: BitbucketSyncRequest): Promise<BitbucketSyncResult> {
  return await readJson<BitbucketSyncResult>(
    await fetch('/api/bitbucket/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  );
}

export async function fetchBitbucketPrRecords(projectId?: number): Promise<BitbucketPrRecord[]> {
  const query = projectId ? `?projectId=${encodeURIComponent(String(projectId))}` : '';
  return await readJson<BitbucketPrRecord[]>(await fetch(`/api/bitbucket/pull-requests${query}`));
}
