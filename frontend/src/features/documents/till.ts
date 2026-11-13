"use client";

import { useQuery } from "@tanstack/react-query";
import { api, type Schemas } from "@/lib/api/client";
import { P, useCan, useShop } from "@/lib/shop-context";

/** The user's open till session at a branch (or any open one when no branch is given); null when none is open. */
export function useTillSession(branchId?: number | null): number | null {
  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Schemas["SessionView"][]>("/till-sessions/current") });
  const list = sessions.data ?? [];
  return (branchId != null ? list.find((x) => x.branchId === branchId) : list[0])?.id ?? null;
}

/** Shop name and header lines for printed documents (address and phones when the user may read settings). */
export function useShopHeader() {
  const shop = useShop();
  const can = useCan();
  const settings = useQuery({
    queryKey: ["settings"],
    queryFn: () => api<Schemas["SettingsView"]>("/settings"),
    enabled: can(P.SETTINGS_VIEW),
    staleTime: 300_000,
  });
  const s = settings.data;
  return { name: s?.businessName ?? shop.shop.name, lines: [s?.address, s?.phones, s?.ntn ? `NTN ${s.ntn}` : null] };
}
