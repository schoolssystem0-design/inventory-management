"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import * as React from "react";
import { api, type Schemas } from "@/lib/api/client";
import { shopBase } from "@/lib/routing";
import { useBrowserValue } from "@/lib/use-browser-value";

export type ShopContext = Schemas["ShopContext"];

export const CONTEXT_KEY = ["shop-context"] as const;

/** GET /shop/context: who is signed in, permissions, modules, branches, plan, banners. */
export function useShopContextQuery() {
  return useQuery({
    queryKey: CONTEXT_KEY,
    queryFn: () => api<ShopContext>("/shop/context"),
    staleTime: 60_000,
  });
}

const Ctx = React.createContext<ShopContext | null>(null);

export function ShopContextProvider({ value, children }: { value: ShopContext; children: React.ReactNode }) {
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useShop(): ShopContext {
  const value = React.useContext(Ctx);
  if (!value) throw new Error("useShop outside the shop app");
  return value;
}

/** Whether the current user has a permission. Hiding is cosmetic; the API checks every call. */
export function useCan() {
  const { permissions } = useShop();
  return React.useCallback((permission: string) => permissions.includes(permission), [permissions]);
}

/** Whether a module (docs/requirements/SHOP-TYPES-AND-MODULES.md) is on for this shop. */
export function useFeature() {
  const { modules } = useShop();
  return React.useCallback((key: string) => modules.some((m) => m.key === key), [modules]);
}

export function useRefreshContext() {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: CONTEXT_KEY });
}

/** Path inside the shop app ("" on the shop's host, "/support" in a support session). */
export function useShopHref() {
  const base = useBrowserValue(shopBase, "");
  return React.useCallback((path: string) => base + (path.startsWith("/") ? path : "/" + path), [base]);
}

export function ShopLink({ href, ...props }: Omit<React.ComponentProps<typeof Link>, "href"> & { href: string }) {
  const toHref = useShopHref();
  return <Link href={toHref(href)} {...props} />;
}

export function useShopRouter() {
  const router = useRouter();
  const toHref = useShopHref();
  return {
    push: (path: string) => router.push(toHref(path)),
    replace: (path: string) => router.replace(toHref(path)),
  };
}

export const P = {
  SETTINGS_VIEW: "settings.view",
  SETTINGS_EDIT: "settings.edit",
  BRANCHES_MANAGE: "branches.manage",
  USERS_VIEW: "users.view",
  USERS_MANAGE: "users.manage",
  AUDIT_VIEW: "audit.view",
  LOGIN_HISTORY_VIEW: "login_history.view",
  CATALOG_VIEW: "catalog.view",
  CATALOG_MANAGE: "catalog.manage",
  CATALOG_PRICES: "catalog.prices",
  CATALOG_IMPORT: "catalog.import",
  CATALOG_EXPORT: "catalog.export",
  LABELS_PRINT: "labels.print",
  COST_VIEW: "cost.view",
  POS_SELL: "pos.sell",
  POS_DISCOUNT: "pos.discount",
  POS_PRICE_OVERRIDE: "pos.price_override",
  SALES_VIEW: "sales.view",
  SALES_RETURN: "sales.return",
  SALES_CANCEL: "sales.cancel",
  QUOTATIONS_MANAGE: "quotations.manage",
  TILL_MANAGE: "till.manage",
  CUSTOMERS_VIEW: "customers.view",
  CUSTOMERS_MANAGE: "customers.manage",
  CUSTOMERS_PAYMENTS: "customers.payments",
  CUSTOMERS_PII: "customers.pii",
  PURCHASING_VIEW: "purchasing.view",
  PURCHASING_MANAGE: "purchasing.manage",
  SUPPLIERS_PAYMENTS: "suppliers.payments",
  STOCK_VIEW: "stock.view",
  STOCK_ADJUST: "stock.adjust",
  STOCK_COUNT: "stock.count",
  STOCK_TRANSFER: "stock.transfer",
  CASH_VIEW: "cash.view",
  EXPENSES_MANAGE: "expenses.manage",
  REPORTS_VIEW: "reports.view",
  DATA_EXPORT: "data.export",
  SERIALS_MANAGE: "serials.manage",
  BUYBACK_MANAGE: "buyback.manage",
  WARRANTY_MANAGE: "warranty.manage",
  REPAIRS_MANAGE: "repairs.manage",
  PRESCRIPTIONS_APPROVE: "prescriptions.approve",
  PROMOTIONS_MANAGE: "promotions.manage",
  LOYALTY_MANAGE: "loyalty.manage",
  INSTALLMENTS_MANAGE: "installments.manage",
  DELIVERY_MANAGE: "delivery.manage",
  SUBSCRIPTION_VIEW: "subscription.view",
  SUPPORT_APPROVE: "support.approve",
} as const;

const BRANCH_KEY = "pos-branch";

/**
 * The branch the user is working in (selling, receiving, counting). Remembered per browser; falls back to the
 * shop's default branch. Users limited to some branches only see those in the context.
 */
function readBranch(): number | null {
  try {
    const v = localStorage.getItem(BRANCH_KEY);
    return v ? Number(v) : null;
  }
  catch {
    return null;
  }
}

function subscribeBranch(onChange: () => void) {
  window.addEventListener("pos:branch", onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener("pos:branch", onChange);
    window.removeEventListener("storage", onChange);
  };
}

export function useBranch(): [Schemas["BranchInfo"] | null, (id: number) => void] {
  const shop = useShop();
  const chosen = React.useSyncExternalStore(subscribeBranch, readBranch, () => null);
  const branch =
    shop.branches.find((b) => b.id === chosen) ?? shop.branches.find((b) => b.isDefault) ?? shop.branches[0] ?? null;
  const choose = React.useCallback((id: number) => {
    try {
      localStorage.setItem(BRANCH_KEY, String(id));
    }
    catch {
      // Not remembered.
    }
    window.dispatchEvent(new Event("pos:branch"));
  }, []);
  return [branch, choose];
}
