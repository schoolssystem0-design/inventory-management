"use client";

import { useSyncExternalStore } from "react";

/** Reads a browser-only value (from the address bar) without an effect; `serverValue` while rendering on the server. */
export function useBrowserValue<T>(read: () => T, serverValue: T): T {
  return useSyncExternalStore(noopSubscribe, read, () => serverValue);
}

function noopSubscribe() {
  return () => {};
}

/** A query-string parameter of the current page (null on the server). */
export function useQueryParam(name: string): string | null {
  return useBrowserValue(() => new URLSearchParams(window.location.search).get(name), null);
}
