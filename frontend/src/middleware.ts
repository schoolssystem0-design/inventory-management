import type { NextRequest } from "next/server";
import { config as proxyConfig, proxy } from "./proxy";

/** Host-based rewrites (platform / shop / landing). See src/lib/routing.ts. */
export function middleware(request: NextRequest) {
  return proxy(request);
}

export const config = proxyConfig;