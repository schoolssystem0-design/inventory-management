"use client";

import * as React from "react";
import { API_BASE, toApiError, type Schemas } from "@/lib/api/client";
import { type CatalogExtras, CatalogIndex, loadSaved, save } from "./catalog-cache";

type State = { index: CatalogIndex | null; extras: CatalogExtras; version: string | null; loading: boolean; error: string | null; savedAt: number | null };

const NO_EXTRAS: CatalogExtras = { offers: [], settings: null };

/**
 * The till's catalogue: starts from the copy saved in IndexedDB, then asks the server with If-None-Match (304 when
 * nothing changed). Refreshes every minute and after each sale, so stock and prices stay current.
 */
export function usePosCatalog(shopCode: string, branchId: number | null) {
  const key = `${shopCode}:${branchId ?? 0}`;
  const [state, setState] = React.useState<State>({ index: null, extras: NO_EXTRAS, version: null, loading: true, error: null, savedAt: null });
  const versionRef = React.useRef<string | null>(null);

  const refresh = React.useCallback(async () => {
    try {
      const headers: Record<string, string> = { Accept: "application/json" };
      if (versionRef.current) headers["If-None-Match"] = `"${versionRef.current}"`;
      const response = await fetch(`${API_BASE}/pos/catalog${branchId ? `?branchId=${branchId}` : ""}`, { credentials: "include", headers, cache: "no-store" });
      if (response.status === 304) {
        setState((s) => ({ ...s, loading: false, error: null }));
        return;
      }
      if (!response.ok) throw await toApiError(response);
      const body = (await response.json()) as Schemas["PosCatalogView"];
      versionRef.current = body.version;
      const extras: CatalogExtras = { offers: body.offers ?? [], settings: body.settings ?? null };
      setState({ index: new CatalogIndex(body.items), extras, version: body.version, loading: false, error: null, savedAt: Date.now() });
      void save(key, body.version, body.items, extras);
    }
    catch (e) {
      setState((s) => ({ ...s, loading: false, error: e instanceof Error ? e.message : "Could not load products" }));
    }
  }, [branchId, key]);

  React.useEffect(() => {
    let cancelled = false;
    versionRef.current = null;
    void (async () => {
      const saved = await loadSaved(key);
      if (cancelled) return;
      if (saved) {
        versionRef.current = saved.version;
        setState({ index: new CatalogIndex(saved.items), extras: saved.extras ?? NO_EXTRAS, version: saved.version, loading: false, error: null, savedAt: saved.savedAt });
      }
      await refresh();
    })();
    const timer = window.setInterval(() => void refresh(), 60_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [key, refresh]);

  return { ...state, refresh };
}
