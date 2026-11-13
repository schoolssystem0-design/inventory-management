"use client";

import { useQuery } from "@tanstack/react-query";
import { X } from "lucide-react";
import * as React from "react";
import { Input } from "@/components/ui/fields";
import { api, type Schemas } from "@/lib/api/client";
import { money } from "@/lib/format";

export type PickedParty = { id: number; name: string; phone?: string | null; balance?: string | null };

/** Search-as-you-type picker for a customer or supplier. */
export function PartyPicker({
  kind,
  value,
  onChange,
  placeholder,
}: {
  kind: "customer" | "supplier";
  value: PickedParty | null;
  onChange: (party: PickedParty | null) => void;
  placeholder?: string;
}) {
  const [q, setQ] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [open, setOpen] = React.useState(false);
  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 200);
    return () => clearTimeout(t);
  }, [q]);
  const path = kind === "customer" ? "/customers" : "/suppliers";
  const results = useQuery({
    queryKey: [`${kind}-picker`, debounced],
    queryFn: () => api<{ items: (Schemas["CustomerListItem"] | Schemas["SupplierListItem"])[] }>(`${path}?q=${encodeURIComponent(debounced)}&active=true&size=8`),
    enabled: open && !value,
  });
  if (value) {
    return (
      <div className="field flex items-center justify-between gap-2">
        <span className="truncate">
          {value.name}
          {value.phone && <span className="ml-2 text-xs text-faint">{value.phone}</span>}
        </span>
        <button type="button" onClick={() => onChange(null)} aria-label={`Clear ${kind}`} className="text-faint hover:text-ink">
          <X className="size-4" />
        </button>
      </div>
    );
  }
  return (
    <div className="relative">
      <Input
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder={placeholder ?? (kind === "customer" ? "Find a customer" : "Find a supplier")}
        aria-label={kind === "customer" ? "Customer" : "Supplier"}
        autoComplete="off"
      />
      {open && results.data && (
        <ul className="glass absolute inset-x-0 top-full z-20 mt-1 max-h-64 overflow-y-auto p-1 shadow-xl">
          {results.data.items.length === 0 && <li className="px-3 py-2 text-sm text-faint">Nothing found</li>}
          {results.data.items.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => {
                  onChange({ id: p.id, name: p.name, phone: p.phone, balance: p.balance });
                  setQ("");
                }}
                className="flex w-full items-center justify-between gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-raised"
              >
                <span>
                  {p.name} <span className="text-xs text-faint">{p.phone ?? p.code}</span>
                </span>
                {p.balance !== "0.00" && <span className="font-mono text-xs text-warn">{money(p.balance)}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
