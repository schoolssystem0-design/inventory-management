"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { Boxes, Home, Lock, LogOut, Menu, Receipt, Search, ShoppingCart, Store, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";
import { toast } from "sonner";
import { CommandPalette } from "@/components/shell/command-palette";
import { ForcedPasswordChange } from "@/components/shell/forced-password";
import { LockScreen, useIdleLock } from "@/components/shell/lock-screen";
import { NotificationBell } from "@/components/shell/notification-bell";
import { visibleNav } from "@/components/shell/nav";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/fields";
import { Skeleton } from "@/components/ui/layout";
import { ApiError, api, errorMessage, fileUrl, resetCsrfToken } from "@/lib/api/client";
import { platformUrl, shopBase } from "@/lib/routing";
import {
  ShopContextProvider,
  useCan,
  useFeature,
  useShop,
  useShopContextQuery,
  useShopHref,
} from "@/lib/shop-context";

/** The signed-in shop app: loads the shop context once, then renders the sidebar, banners and page. */
export function AppShell({ children }: { children: React.ReactNode }) {
  const context = useShopContextQuery();
  const router = useRouter();
  const error = context.error;

  React.useEffect(() => {
    if (!(error instanceof ApiError)) return;
    if (error.status === 401) {
      if (shopBase() === "/support") window.location.href = platformUrl("/");
      else router.replace("/login");
    } else if (error.code === "no_shop") {
      window.location.href = platformUrl("/");
    }
  }, [error, router]);

  if (error instanceof ApiError && error.code === "password_change_required") {
    return <ForcedPasswordChange onDone={() => context.refetch()} />;
  }
  if (!context.data) {
    return error && !(error instanceof ApiError && (error.status === 401 || error.code === "no_shop")) ? (
      <div className="flex min-h-dvh items-center justify-center p-6 text-center text-sm text-muted">
        <div>
          <p>{errorMessage(error)}</p>
          <button className="mt-3 font-semibold text-aqua" onClick={() => context.refetch()}>
            Try again
          </button>
        </div>
      </div>
    ) : (
      <ShellSkeleton />
    );
  }
  return (
    <ShopContextProvider value={context.data}>
      <Shell>{children}</Shell>
    </ShopContextProvider>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  const shop = useShop();
  const can = useCan();
  const toHref = useShopHref();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = React.useState(false);
  const [paletteOpen, setPaletteOpen] = React.useState(false);
  const feature = useFeature();
  const groups = visibleNav(can, feature);
  const lock = useIdleLock(shop.settings.sessionIdleMinutes, shop.user.actorType === "USER" && shop.user.pinSet);

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Compare against the visible path without the support prefix; the most specific matching item is active.
  const visible = pathname.replace(/^\/(shop|support)(?=\/|$)/, "") || "/";
  const matches = (href: string) => (href === "/" ? visible === "/" : visible === href || visible.startsWith(href + "/"));
  const activeHref = groups
    .flatMap((g) => g.items.map((i) => i.href))
    .filter(matches)
    .sort((a, b) => b.length - a.length)[0];

  const nav = (
    <nav aria-label="Main" className="space-y-5">
      {groups.map((group) => (
        <div key={group.label || "main"}>
          {group.label && <p className="mb-1.5 px-3 text-[11px] font-semibold tracking-wider text-faint uppercase">{group.label}</p>}
          <ul className="space-y-0.5">
            {group.items.map((item) => {
              const active = item.href === activeHref;
              return (
                <li key={item.href}>
                  <Link
                    href={toHref(item.href)}
                    aria-current={active ? "page" : undefined}
                    onClick={() => setMenuOpen(false)}
                    className={cn(
                      "flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium transition-colors",
                      active ? "bg-raised text-ink" : "text-muted hover:bg-raised/60 hover:text-ink",
                    )}
                  >
                    <item.icon className={cn("size-[18px]", active ? "text-aqua" : "text-faint")} aria-hidden />
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );

  return (
    <div className="min-h-dvh lg:pl-64 print:pl-0">
      {/* Sidebar (desktop) */}
      <aside className="no-print fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-line bg-deep lg:flex">
        <ShopTitle />
        <div className="flex-1 overflow-y-auto px-3 pb-4">{nav}</div>
        <UserFooter />
      </aside>

      {/* Top bar (phone and tablet) */}
      <header className="no-print sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-line bg-deep/95 px-3 backdrop-blur lg:hidden">
        <button className="rounded-lg p-2 text-muted hover:bg-raised" aria-label="Open menu" onClick={() => setMenuOpen(true)}>
          <Menu className="size-5" />
        </button>
        <p className="min-w-0 flex-1 truncate font-semibold">{shop.shop.name}</p>
        <button className="rounded-lg p-2 text-muted hover:bg-raised" aria-label="Search" onClick={() => setPaletteOpen(true)}>
          <Search className="size-5" />
        </button>
        <NotificationBell />
      </header>

      {menuOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu">
          <button className="absolute inset-0 bg-black/60" aria-label="Close menu" onClick={() => setMenuOpen(false)} />
          <div className="absolute inset-y-0 left-0 flex w-72 flex-col bg-deep shadow-2xl">
            <div className="flex items-center justify-between pr-2">
              <ShopTitle />
              <button className="rounded-lg p-2 text-muted hover:bg-raised" aria-label="Close menu" onClick={() => setMenuOpen(false)}>
                <X className="size-5" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-3 pb-4">{nav}</div>
            <UserFooter />
          </div>
        </div>
      )}

      <Banners />

      <main
        className={cn(
          "mx-auto px-4 pt-6 pb-28 sm:px-6 lg:pb-12 print:max-w-none print:p-0",
          visible.startsWith("/pos") ? "max-w-none lg:pt-4" : "max-w-6xl",
        )}
      >
        {children}
      </main>

      {/* Bottom bar (phone): Home, Sales, POS, Products, More */}
      <nav aria-label="Quick" className="no-print fixed inset-x-0 bottom-0 z-30 grid grid-cols-5 border-t border-line bg-deep/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden">
        <BottomLink href="/" label="Home" icon={Home} active={visible === "/"} />
        {can("sales.view") ? <BottomLink href="/sales" label="Sales" icon={Receipt} active={visible.startsWith("/sales")} /> : <span />}
        {can("pos.sell") ? <BottomLink href="/pos" label="POS" icon={ShoppingCart} active={visible.startsWith("/pos")} /> : <span />}
        {can("catalog.view") ? (
          <BottomLink href="/products" label="Products" icon={Boxes} active={visible.startsWith("/products")} />
        ) : (
          <span />
        )}
        <button className="flex flex-col items-center gap-0.5 py-2 text-[11px] text-muted" onClick={() => setMenuOpen(true)}>
          <Menu className="size-5" aria-hidden />
          More
        </button>
      </nav>

      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
      {lock.locked && <LockScreen onUnlocked={lock.unlock} />}
    </div>
  );
}

function BottomLink({ href, label, icon: Icon, active }: { href: string; label: string; icon: typeof Home; active: boolean }) {
  const toHref = useShopHref();
  return (
    <Link href={toHref(href)} className={cn("flex flex-col items-center gap-0.5 py-2 text-[11px]", active ? "text-aqua" : "text-muted")}>
      <Icon className="size-5" aria-hidden />
      {label}
    </Link>
  );
}

function ShopTitle() {
  const shop = useShop();
  const logo = fileUrl(shop.shop.logoFileId);
  return (
    <div className="flex items-center gap-3 px-5 py-5">
      {logo ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={logo} alt="" className="size-9 rounded-lg object-cover" />
      ) : (
        <span className="grid size-9 place-items-center rounded-lg bg-aqua/15 text-aqua">
          <Store className="size-5" aria-hidden />
        </span>
      )}
      <div className="min-w-0">
        <p className="truncate text-sm font-semibold">{shop.shop.name}</p>
        <p className="truncate text-xs text-faint">{shop.shop.typeName}</p>
      </div>
    </div>
  );
}

function UserFooter() {
  const shop = useShop();
  const canLock = shop.user.actorType === "USER" && shop.user.pinSet;
  const client = useQueryClient();
  const router = useRouter();
  const support = shop.user.actorType === "PLATFORM";
  async function signOut() {
    try {
      if (support) {
        await api("/platform/support-session", { method: "DELETE" });
        window.location.href = platformUrl("/");
        return;
      }
      await api("/auth/logout", { method: "POST" });
    } catch {
      // Signed out anyway.
    }
    resetCsrfToken();
    client.clear();
    router.replace("/login");
  }
  return (
    <div className="flex items-center gap-3 border-t border-line px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{shop.user.displayName}</p>
        <p className="truncate text-xs text-faint">{shop.user.roleName}</p>
      </div>
      <span className="hidden lg:inline">
        <NotificationBell />
      </span>
      {canLock && (
        <button
          className="rounded-lg p-2 text-faint hover:bg-raised hover:text-ink"
          onClick={() => window.dispatchEvent(new Event("pos:lock"))}
          aria-label="Lock screen"
          title="Lock screen (unlock with your PIN)"
        >
          <Lock className="size-4" />
        </button>
      )}
      <button className="rounded-lg p-2 text-faint hover:bg-raised hover:text-ink" onClick={signOut} aria-label={support ? "Leave support session" : "Sign out"} title={support ? "Leave support session" : "Sign out"}>
        <LogOut className="size-4" />
      </button>
    </div>
  );
}

/** Trial, overdue, read-only and support banners from the shop context. */
function Banners() {
  const shop = useShop();
  const supportViewer = shop.user.actorType === "PLATFORM";
  return (
    <div className="no-print space-y-px">
      {supportViewer && <SupportBar />}
      {shop.banners
        .filter((b) => !(supportViewer && b.kind === "SUPPORT"))
        .map((banner) => (
          <div
            key={banner.kind}
            role="status"
            className={cn(
              "px-4 py-2 text-center text-[13px] font-medium",
              banner.kind === "READ_ONLY" && "bg-bad/15 text-bad",
              banner.kind === "GRACE" && "bg-warn/15 text-warn",
              banner.kind === "PAYMENT_DUE" && "bg-warn/10 text-warn",
              banner.kind === "TRIAL" && "bg-aqua/10 text-aqua",
              banner.kind === "SUPPORT" && "bg-info/15 text-info",
            )}
          >
            {banner.message}
          </div>
        ))}
    </div>
  );
}

/** For SelectCode support inside a shop: read-only until the owner's approval code is entered. */
function SupportBar() {
  const shop = useShop();
  const client = useQueryClient();
  const [code, setCode] = React.useState("");
  const approve = useMutation({
    mutationFn: () => api("/platform/support-session/approve", { method: "POST", body: { code } }),
    onSuccess: () => {
      toast.success("Changes are now allowed for this session");
      client.invalidateQueries();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const approved = shop.support?.writeApproved;
  return (
    <div className="flex flex-wrap items-center justify-center gap-3 bg-info/15 px-4 py-2 text-[13px] text-info">
      <span className="font-semibold">Support session · {shop.shop.name}</span>
      <span>{approved ? "Changes allowed" : "Read-only"}</span>
      {!approved && (
        <form
          className="flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            approve.mutate();
          }}
        >
          <Input
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
            placeholder="Owner's code"
            aria-label="Owner's approval code"
            className="h-8 w-32 py-1 font-mono"
            inputMode="numeric"
          />
          <Button size="sm" variant="secondary" type="submit" loading={approve.isPending} disabled={code.length !== 6}>
            Allow changes
          </Button>
        </form>
      )}
      <a href={platformUrl("/")} className="font-semibold underline">
        Back to platform
      </a>
    </div>
  );
}

function ShellSkeleton() {
  return (
    <div className="min-h-dvh lg:pl-64" aria-busy="true" aria-label="Loading">
      <aside className="fixed inset-y-0 left-0 hidden w-64 space-y-3 border-r border-line bg-deep p-5 lg:block">
        <Skeleton className="h-9 w-40" />
        {Array.from({ length: 8 }).map((_, i) => (
          <Skeleton key={i} className="h-7" />
        ))}
      </aside>
      <div className="mx-auto max-w-6xl space-y-4 px-4 pt-8 sm:px-6">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-40" />
        <Skeleton className="h-64" />
      </div>
    </div>
  );
}
