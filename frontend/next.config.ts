import type { NextConfig } from "next";

function pathBasedRewrites(): { source: string; destination: string }[] {
  const root = (process.env.NEXT_PUBLIC_ROOT_DOMAIN ?? "")
    .replace(/^https?:\/\//, "")
    .replace(/:\d+$/, "")
    .toLowerCase();
  const pathBased = process.env.NEXT_PUBLIC_PATH_ROUTING === "1" || root.endsWith(".vercel.app");
  if (!pathBased) return [];
  const platform = (p: string) => ({ source: p, destination: `/platform${p === "/" ? "" : p}` });
  return [
    { source: "/login", destination: "/platform/login" },
    platform("/shops"),
    { source: "/shops/:path*", destination: "/platform/shops/:path*" },
    platform("/types"),
    platform("/plans"),
    platform("/users"),
    { source: "/users/:path*", destination: "/platform/users/:path*" },
    platform("/settings"),
    platform("/audit"),
    { source: "/s/:code", destination: "/shop" },
    { source: "/s/:code/:path*", destination: "/shop/:path*" },
  ];
}

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // The camera is used for barcode scanning on this site only.
  { key: "Permissions-Policy", value: "camera=(self), microphone=(), geolocation=()" },
  { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
  // Never framed by another site; no plugins; forms and <base> only to our own origin (SECURITY.md §2).
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'" },
];

const nextConfig: NextConfig = {
  poweredByHeader: false,
  // Lets phones on the same Wi-Fi and *.localhost shop hosts load the dev server.
  allowedDevOrigins: ["*.localhost", ...(process.env.DEV_ALLOWED_ORIGINS?.split(",") ?? [])],
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
  async rewrites() {
    return { beforeFiles: pathBasedRewrites() };
  },
};

export default nextConfig;
