/**
 * Which part of the app a request belongs to (docs/ARCHITECTURE.md §1):
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
  const area = areaOf(window.location.host);
  return area.kind === "shop" ? area.code : null;
}

/** Path prefix for shop links: "" on a shop host, "/support" when a platform user is in support mode. */
export function shopBase(): string {
  if (typeof window === "undefined") return "";
  return areaOf(window.location.host).kind === "platform" ? "/support" : "";
}

/** URL of a shop's app (for links from the landing page and the platform portal). */
export function shopUrl(code: string, path = "/") {
  if (typeof window === "undefined") return path;
  const { protocol, port } = window.location;
  return `${protocol}//${code}.${rootDomain()}${port ? ":" + port : ""}${path}`;
}

export function platformUrl(path = "/") {
  if (typeof window === "undefined") return path;
  const { protocol, port } = window.location;
  return `${protocol}//admin.${rootDomain()}${port ? ":" + port : ""}${path}`;
}

