"use client";

import { useCallback } from "react";
import { currentShopCode, shopBase, usePathBasedRouting } from "@/lib/routing";
import { useBrowserValue } from "@/lib/use-browser-value";

/** Prefix for links on the shop sign-in, setup, reset and forgot pages (`/s/<code>` on Vercel). */
export function useShopPublicHref() {
  const base = useBrowserValue(() => {
    const fromHost = shopBase();
    if (fromHost && fromHost !== "/support") return fromHost;
    const code = currentShopCode();
    if (code && usePathBasedRouting()) return `/s/${code}`;
    return "";
  }, "");
  return useCallback((path: string) => `${base}${path.startsWith("/") ? path : `/${path}`}`, [base]);
}

/** Build `/s/<code>/login` (or subdomain equivalent) from an API shop URL. */
export function shopSignInUrl(shopUrl: string): string {
  const u = shopUrl.replace(/\/$/, "");
  if (u.endsWith("/login")) return u;
  return `${u}/login`;
}