/**
 * Sales waiting to reach the server (POS-12). Each sale keeps its Idempotency-Key, so sending it twice books it once.
 * Kept in localStorage so a reload or a dropped connection does not lose a bill; retried every few seconds.
 */
import { ApiError, api, type Schemas } from "@/lib/api/client";

export type QueuedSale = {
  key: string;
  body: unknown;
  createdAt: number;
  attempts: number;
  lastError: string | null;
  total: string;
};

const STORAGE = "pos-queue";

function read(): QueuedSale[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE) ?? "[]") as QueuedSale[];
  }
  catch {
    return [];
  }
}

function write(items: QueuedSale[]) {
  try {
    localStorage.setItem(STORAGE, JSON.stringify(items));
  }
  catch {
    // Storage blocked: the sale stays in memory for this page.
  }
  listeners.forEach((l) => l(items));
}

const listeners = new Set<(items: QueuedSale[]) => void>();

export function subscribe(listener: (items: QueuedSale[]) => void) {
  listeners.add(listener);
  listener(read());
  return () => {
    listeners.delete(listener);
  };
}

export function pending(): QueuedSale[] {
  return read();
}

export function newKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `k${Date.now()}${Math.random().toString(36).slice(2)}`;
}

/** Network down or the server not answering: keep the sale and try again. Anything else is a real answer. */
export function isRetryable(error: unknown): boolean {
  if (error instanceof ApiError) return error.status >= 500 || error.status === 0 || error.status === 429;
  return error instanceof TypeError;
}

export async function send(key: string, body: unknown, headers?: Record<string, string>): Promise<Schemas["SaleView"]> {
  return api<Schemas["SaleView"]>("/sales", { method: "POST", body, headers: { "Idempotency-Key": key, ...headers } });
}

export function enqueue(item: Omit<QueuedSale, "attempts" | "lastError" | "createdAt">) {
  const items = read().filter((i) => i.key !== item.key);
  write([...items, { ...item, attempts: 0, lastError: null, createdAt: Date.now() }]);
}

export function drop(key: string) {
  write(read().filter((i) => i.key !== key));
}

let running = false;

/** Try every queued sale once. Returns the sales that went through. */
export async function flush(): Promise<Schemas["SaleView"][]> {
  if (running) return [];
  running = true;
  const done: Schemas["SaleView"][] = [];
  try {
    for (const item of read()) {
      try {
        done.push(await send(item.key, item.body));
        drop(item.key);
      }
      catch (e) {
        const message = e instanceof Error ? e.message : "Failed";
        write(read().map((i) => (i.key === item.key ? { ...i, attempts: i.attempts + 1, lastError: message } : i)));
        if (isRetryable(e)) break;
      }
    }
  }
  finally {
    running = false;
  }
  return done;
}
