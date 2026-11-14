"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { Building, Gauge, LayoutTemplate, LogOut, Menu, Plus, ReceiptText, ScrollText, Settings, Store, UserCog, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";
import { Skeleton } from "@/components/ui/layout";
import { ApiError, api, resetCsrfToken, type Schemas } from "@/lib/api/client";
import { BRAND } from "@/lib/brand";
import { consoleHref } from "@/lib/routing";

type Me = Schemas["PlatformMe"];

const PlatformCtx = React.createContext<Me | null>(null);

export function usePlatformMe(): Me {
  const me = React.useContext(PlatformCtx);
  if (!me) throw new Error("usePlatformMe outside the platform portal");
  return me;
}

export function useCapability() {
  const me = usePlatformMe();
  return (capability: string) => me.capabilities.includes(capability);
}

const NAV = [
  { href: "/", label: "Overview", icon: Gauge, cap: "platform.shops.view" },
  { href: "/shops", label: "Shops", icon: Store, cap: "platform.shops.view" },
  { href: "/shops/new", label: "New shop", icon: Plus, cap: "platform.shops.create" },
  { href: "/types", label: "Shop types", icon: LayoutTemplate },
  { href: "/plans", label: "Plans", icon: ReceiptText },
  { href: "/users", label: "Platform users", icon: UserCog, cap: "platform.admin" },
  { href: "/settings", label: "Settings", icon: Settings },
  { href: "/audit", label: "Audit log", icon: ScrollText, cap: "platform.shops.view" },
];

/** The super admin portal frame (admin.<domain>). */
export function PlatformShell({ children }: { children: React.ReactNode }) {
  const me = useQuery({ queryKey: ["platform-me"], queryFn: () => api<Me>("/platform/auth/me") });
  const router = useRouter();
  const pathname = usePathname();
  const client = useQueryClient();
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    if (me.error instanceof ApiError && (me.error.status === 401 || me.error.status === 403)) router.replace("/login");
  }, [me.error, router]);

  if (!me.data) {
    return (
      <div className="mx-auto max-w-5xl space-y-4 p-8" aria-busy="true">
        <Skeleton className="h-8 w-60" />
        <Skeleton className="h-64" />
      </div>
    );
  }
  const visible = pathname.replace(/^\/platform(?=\/|$)/, "") || "/";
  const items = NAV.filter((n) => !n.cap || me.data.capabilities.includes(n.cap));
  const active = items
    .map((n) => n.href)
    .filter((h) => (h === "/" ? visible === "/" : visible === h || visible.startsWith(h + "/")))
    .sort((a, b) => b.length - a.length)[0];

  async function signOut() {
    try {
      await api("/auth/logout", { method: "POST" });
    } catch {
      // Signed out anyway.
    }
    resetCsrfToken();
    client.clear();
    router.replace("/login");
  }

  const nav = (
    <nav aria-label="Platform" className="space-y-0.5 px-3">
      {items.map((item) => (
        <Link
          key={item.href}
          href={consoleHref(item.href)}
          aria-current={active === item.href ? "page" : undefined}
          onClick={() => setOpen(false)}
          className={cn(
            "flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium",
            active === item.href ? "bg-raised text-ink" : "text-muted hover:bg-raised/60 hover:text-ink",
          )}
        >
          <item.icon className={cn("size-[18px]", active === item.href ? "text-aqua" : "text-faint")} aria-hidden />
          {item.label}
        </Link>
      ))}
    </nav>
  );
  const brand = (
    <div className="flex items-center gap-3 px-5 py-5">
      <span className="grid size-9 place-items-center rounded-lg bg-aqua/15 text-aqua">
        <Building className="size-5" aria-hidden />
      </span>
      <div>
        <p className="text-sm font-semibold">{BRAND.product}</p>
        <p className="text-xs text-aqua">Platform</p>
      </div>
    </div>
  );
  const footer = (
    <div className="flex items-center gap-3 border-t border-line px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{me.data.name}</p>
        <p className="truncate text-xs text-faint">{me.data.role.replace("_", " ").toLowerCase()}</p>
      </div>
      <button className="rounded-lg p-2 text-faint hover:bg-raised hover:text-ink" onClick={signOut} aria-label="Sign out" title="Sign out">
        <LogOut className="size-4" />
      </button>
    </div>
  );

  return (
    <PlatformCtx.Provider value={me.data}>
      <div className="min-h-dvh lg:pl-60">
        <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col border-r border-line bg-deep lg:flex">
          {brand}
          <div className="flex-1 overflow-y-auto">{nav}</div>
          {footer}
        </aside>
        <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-line bg-deep/95 px-3 lg:hidden">
          <button className="rounded-lg p-2 text-muted hover:bg-raised" aria-label="Open menu" onClick={() => setOpen(true)}>
            <Menu className="size-5" />
          </button>
          <p className="font-semibold">{BRAND.platform}</p>
        </header>
        {open && (
          <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu">
            <button className="absolute inset-0 bg-black/60" aria-label="Close menu" onClick={() => setOpen(false)} />
            <div className="absolute inset-y-0 left-0 flex w-72 flex-col bg-deep">
              <div className="flex items-center justify-between pr-2">
                {brand}
                <button className="rounded-lg p-2" aria-label="Close menu" onClick={() => setOpen(false)}>
                  <X className="size-5" />
                </button>
              </div>
              <div className="flex-1">{nav}</div>
              {footer}
            </div>
          </div>
        )}
        <main className="mx-auto max-w-6xl px-4 pt-6 pb-16 sm:px-6">{children}</main>
      </div>
    </PlatformCtx.Provider>
  );
}
