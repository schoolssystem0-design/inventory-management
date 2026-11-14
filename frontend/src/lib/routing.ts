/**
 * Which part of the app a request belongs to (docs/architecture/ARCHITECTURE.md §1):
 * - `admin.<root>`        → the platform portal (rewritten to /platform/...); `/support/...` → the shop app in support mode
 * - `<shopcode>.<root>`   → that shop's app (rewritten to /shop/...)
 * - `<root>`              → the landing page where a shop code is entered
 */
export type Area =
  | { kind: "platform" }
  | { kind: "shop"; code: string }
  | { kind: "landing" };

export type RouteDecision = { kind: "next" } | { kind: "rewrite"; pathname: string } | { kind: "notFound" };

export const SESSION_COOKIE = "POS_SESSION";

export function rootDomain(value = process.env.NEXT_PUBLIC_ROOT_DOMAIN) {
  return (value ?? "localhost").replace(/^https?:\/\//, "").replace(/:\d+$/, "").toLowerCase();
}

export function stripPort(host: string) {
  return host.replace(/:\d+$/, "").toLowerCase();
}

const SHOP_CODE = /^[a-z0-9]([a-z0-9-]{0,28}[a-z0-9])?$/;

/** Console paths that live under `/platform` on the apex host when using path-based routing. */
const PLATFORM_APEX_PATH = /^\/(shops|types|plans|users|settings|audit)(\/.*)?$/;

/** On *.vercel.app, wildcard shop/admin subdomains are often unavailable — use path URLs instead. */
export function usePathBasedRouting(root = rootDomain()): boolean {
  if (process.env.NEXT_PUBLIC_PATH_ROUTING === "1") return true;
  return root.endsWith(".vercel.app");
}

function isApexHost(host: string, root: string): boolean {
  const h = stripPort(host);
  return h === root || h === `www.${root}`;
}

export function areaOf(host: string, root = rootDomain()): Area {
  const h = stripPort(host);
  if (h === `admin.${root}`) return { kind: "platform" };
  if (h.endsWith(`.${root}`)) {
    const code = h.slice(0, -(root.length + 1));
    if (SHOP_CODE.test(code)) return { kind: "shop", code };
  }
  return { kind: "landing" };
}

/** Internal folders that must only be reached through their own host. */
const INTERNAL = ["/platform", "/shop", "/landing"];

export function decideRoute(host: string, pathname: string, root = rootDomain()): RouteDecision {
  const pathBased = usePathBasedRouting(root);

  if (pathBased && isApexHost(host, root)) {
    const shopPath = pathname.match(/^\/s\/([a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?)(\/.*)?$/);
    if (shopPath) {
      const rest = shopPath[2] ?? "";
      return { kind: "rewrite", pathname: `/shop${rest || ""}` };
    }
    if (pathname === "/login") return { kind: "rewrite", pathname: "/platform/login" };
    if (pathname === "/platform" || pathname.startsWith("/platform/")) {
      return { kind: "rewrite", pathname: pathname };
    }
    if (PLATFORM_APEX_PATH.test(pathname)) {
      return { kind: "rewrite", pathname: `/platform${pathname}` };
    }
  }

  if (INTERNAL.some((p) => pathname === p || pathname.startsWith(p + "/"))) return { kind: "notFound" };
  const area = areaOf(host, root);
  const rest = pathname === "/" ? "" : pathname;
  switch (area.kind) {
    case "platform":
      if (pathname === "/support" || pathname.startsWith("/support/")) {
        return { kind: "rewrite", pathname: "/shop" + pathname.slice("/support".length) };
      }
      return { kind: "rewrite", pathname: "/platform" + rest };
    case "shop":
      if (pathname === "/print" || pathname.startsWith("/print/")) return { kind: "rewrite", pathname: "/shop" + rest };
      return { kind: "rewrite", pathname: "/shop" + rest };
    default:
      return { kind: "rewrite", pathname: "/landing" + rest };
  }
}

/** Shop code of the current page (browser only): from the host, or null on the platform host (support mode). */
export function currentShopCode(): string | null {
  if (typeof window === "undefined") return null;
  if (usePathBasedRouting()) {
    const m = window.location.pathname.match(/^\/s\/([a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?)/);
    if (m) return m[1];
  }
  const area = areaOf(window.location.host);
  return area.kind === "shop" ? area.code : null;
}

/** Path prefix for shop links: `/s/<code>` on the apex host, "" on a shop subdomain, "/support" in support mode. */
export function shopBase(): string {
  if (typeof window === "undefined") return "";
  if (areaOf(window.location.host).kind === "platform") return "/support";
  if (usePathBasedRouting()) {
    const code = currentShopCode();
    if (code) return `/s/${code}`;
  }
  return "";
}

/** Rewrites `https://<code>.<root>/…` to `/s/<code>/…` when the app is on path-based routing (e.g. Vercel). */
export function rewriteLegacyShopLink(url: string, root = rootDomain()): string {
  if (!usePathBasedRouting(root) || !url) return url;
  try {
    const u = new URL(url);
    const suffix = `.${root}`;
    if (!u.hostname.endsWith(suffix)) return url;
    const code = u.hostname.slice(0, -suffix.length);
    if (!SHOP_CODE.test(code) || code === "admin" || code === "www") return url;
    return `${u.origin}/s/${code}${u.pathname}${u.search}`;
  } catch {
    return url;
  }
}

/** URL of a shop's app (for links from the landing page and the platform portal). */
export function shopUrl(code: string, path = "/") {
  if (typeof window === "undefined") return path;
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (usePathBasedRouting()) return `/s/${code}${normalized}`;
  const { protocol, port } = window.location;
  return `${protocol}//${code}.${rootDomain()}${port ? ":" + port : ""}${normalized}`;
}

/** Visible URL path for platform console routes (mirrors subdomain-style `/shops` on vercel.app). */
export function platformPath(path = "/") {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (usePathBasedRouting()) return `/platform${normalized === "/" ? "" : normalized}`;
  return normalized;
}

export function platformUrl(path = "/") {
  if (typeof window === "undefined") {
    return platformPath(path === "/login" ? "/login" : path.startsWith("/platform") ? path.replace(/^\/platform/, "") || "/" : path);
  }
  const normalized = path === "/" ? "" : path.startsWith("/") ? path : `/${path}`;
  if (usePathBasedRouting()) {
    return normalized === "/login" ? "/login" : platformPath(normalized);
  }
  const { protocol, port } = window.location;
  return `${protocol}//admin.${rootDomain()}${port ? ":" + port : ""}${normalized || "/"}`;
}

/**
 * Platform console links for {@link Link} / {@link useRouter}. On path-based routing (e.g. vercel.app) uses real
 * `/platform/...` app routes so client navigation does not 404; the browser bar can still show `/shops/...` via rewrites.
 */
export function consoleHref(path: string): string {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (usePathBasedRouting()) {
    if (normalized === "/login" || normalized.startsWith("/login?")) return normalized;
    return `/platform${normalized === "/" ? "" : normalized}`;
  }
  return normalized;
}

