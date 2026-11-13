import type { components } from "./schema";

/** Types of the API, generated from backend/openapi.json (`npm run gen:api`). */
export type Schemas = components["schemas"];

/** Same-origin API through the Next.js proxy route (src/app/api/v1/[...path]/route.ts). */
export const API_BASE = "/api/v1";

export type FieldError = { field: string; code: string; message: string };

/** A failed API call, parsed from the backend's problem response (docs/API-CONVENTIONS.md §3). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly fieldErrors: FieldError[] = [],
    readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** The message for one field, if the server named it. */
  field(name: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === name || e.field.startsWith(name + "."))?.message;
  }
}

const UNSAFE = new Set(["POST", "PUT", "PATCH", "DELETE"]);

let csrfToken: string | null = null;

async function fetchCsrfToken(): Promise<string> {
  const response = await fetch(`${API_BASE}/auth/csrf`, { credentials: "include" });
  if (!response.ok) throw await toApiError(response);
  const body = (await response.json()) as { token?: string };
  csrfToken = body.token ?? null;
  if (!csrfToken) throw new ApiError(500, "csrf_missing", "Could not start a secure session. Reload the page.");
  return csrfToken;
}

/** Forget the cached CSRF token (after sign-out or sign-in). */
export function resetCsrfToken() {
  csrfToken = null;
}

export async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as Record<string, unknown> & {
      code?: string;
      detail?: string;
      requestId?: string;
      errors?: FieldError[];
    };
    const { code, detail, requestId, errors, ...extra } = problem;
    return new ApiError(
      response.status,
      code ?? `http_${response.status}`,
      detail ?? (response.statusText || "Something went wrong"),
      requestId,
      Array.isArray(errors) ? errors : [],
      extra,
    );
  } catch {
    return new ApiError(response.status, `http_${response.status}`, response.statusText || "Something went wrong");
  }
}

export type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  form?: FormData;
  headers?: Record<string, string>;
  signal?: AbortSignal;
};

async function send(path: string, { method = "GET", body, form, headers: extra, signal }: RequestOptions, accept: string) {
  const attempt = (token: string | null) => {
    const headers: Record<string, string> = { Accept: accept, ...extra };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers["X-XSRF-TOKEN"] = token;
    return fetch(`${API_BASE}${path}`, {
      method,
      credentials: "include",
      headers,
      signal,
      body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
    });
  };
  const unsafe = UNSAFE.has(method);
  let response = await attempt(unsafe ? (csrfToken ?? (await fetchCsrfToken())) : null);
  if (unsafe && response.status === 403) {
    const error = await toApiError(response.clone());
    // A stale CSRF token (after sign-in/out) comes back as plain "forbidden": refresh it once and retry.
    if (error.code === "forbidden") response = await attempt(await fetchCsrfToken());
  }
  if (!response.ok) throw await toApiError(response);
  return response;
}

/** Calls the API with the session cookie; state-changing calls carry the CSRF token. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options, "application/json");
  if (response.status === 204 || response.status === 202) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Calls an endpoint that returns a file (Excel exports and templates). */
export async function apiBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const response = await send(path, options, "*/*");
  return response.blob();
}

/** Saves a downloaded file. */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** URL of a stored file (logo, product image) for <img src>. */
export function fileUrl(fileId: number | null | undefined) {
  return fileId == null ? null : `${API_BASE}/files/${fileId}`;
}

/** Any error as a short message for toasts. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "Something went wrong";
}
