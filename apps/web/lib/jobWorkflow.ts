import type { JobAcceptedResponse, JobType } from "@/lib/api/jobs";

const WORKFLOW_SCHEMA_VERSION = 1;

export type JobWorkflowMetadata = {
  schemaVersion: typeof WORKFLOW_SCHEMA_VERSION;
  jobId: string;
  jobType: JobType | null;
  generation: string;
  startedAt: string;
};

export type ActiveJobWorkflow<TContext> = JobWorkflowMetadata & {
  context: TContext | null;
  restored: boolean;
};

export type JobWorkflowStorage = {
  local: Storage;
  session: Storage;
};

export function createGenerationToken() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function persistJobWorkflow<TContext>(
  storageKey: string,
  accepted: JobAcceptedResponse,
  context: TContext | null,
  storage: JobWorkflowStorage,
  generation = createGenerationToken()
): ActiveJobWorkflow<TContext> {
  removePreviousContext(storageKey, storage);

  const metadata: JobWorkflowMetadata = {
    schemaVersion: WORKFLOW_SCHEMA_VERSION,
    jobId: accepted.jobId,
    jobType: accepted.jobType,
    generation,
    startedAt: new Date().toISOString()
  };

  storage.local.setItem(storageKey, JSON.stringify(metadata));
  if (context !== null) {
    storage.session.setItem(contextStorageKey(storageKey, generation), JSON.stringify(context));
  }

  return { ...metadata, context, restored: false };
}

export function restoreJobWorkflow<TContext>(
  storageKey: string,
  storage: JobWorkflowStorage
): ActiveJobWorkflow<TContext> | null {
  const rawMetadata = storage.local.getItem(storageKey);
  if (!rawMetadata) {
    return null;
  }

  const metadata = parseMetadata(rawMetadata);
  if (!metadata) {
    storage.local.removeItem(storageKey);
    return null;
  }

  if (!rawMetadata.trim().startsWith("{")) {
    storage.local.setItem(storageKey, JSON.stringify(metadata));
  }

  const rawContext = storage.session.getItem(contextStorageKey(storageKey, metadata.generation));
  return {
    ...metadata,
    context: parseContext<TContext>(rawContext),
    restored: true
  };
}

export function clearJobWorkflow(
  storageKey: string,
  generation: string,
  storage: JobWorkflowStorage
) {
  const current = readMetadata(storageKey, storage.local);
  if (!current || current.generation === generation) {
    storage.local.removeItem(storageKey);
  }
  storage.session.removeItem(contextStorageKey(storageKey, generation));
}

export function isCurrentWorkflowGeneration(
  active: Pick<JobWorkflowMetadata, "generation"> | null,
  generation: string
) {
  return active?.generation === generation;
}

export function contextStorageKey(storageKey: string, generation: string) {
  return `${storageKey}:context:${generation}`;
}

function removePreviousContext(storageKey: string, storage: JobWorkflowStorage) {
  const previous = readMetadata(storageKey, storage.local);
  if (previous) {
    storage.session.removeItem(contextStorageKey(storageKey, previous.generation));
  }
}

function readMetadata(storageKey: string, storage: Storage) {
  const raw = storage.getItem(storageKey);
  return raw ? parseMetadata(raw) : null;
}

function parseMetadata(raw: string): JobWorkflowMetadata | null {
  if (!raw.trim().startsWith("{")) {
    const jobId = raw.trim();
    return jobId
      ? {
          schemaVersion: WORKFLOW_SCHEMA_VERSION,
          jobId,
          jobType: null,
          generation: `legacy-${jobId}`,
          startedAt: new Date().toISOString()
        }
      : null;
  }

  try {
    const parsed = JSON.parse(raw) as Partial<JobWorkflowMetadata>;
    if (
      parsed.schemaVersion !== WORKFLOW_SCHEMA_VERSION ||
      typeof parsed.jobId !== "string" ||
      typeof parsed.generation !== "string" ||
      typeof parsed.startedAt !== "string"
    ) {
      return null;
    }
    return {
      schemaVersion: WORKFLOW_SCHEMA_VERSION,
      jobId: parsed.jobId,
      jobType: parsed.jobType ?? null,
      generation: parsed.generation,
      startedAt: parsed.startedAt
    };
  }
  catch {
    return null;
  }
}

function parseContext<TContext>(raw: string | null): TContext | null {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as TContext;
  }
  catch {
    return null;
  }
}
