"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Checkbox, Field, Input, Select } from "@/components/ui/fields";
import { Badge, Empty, PageHeader, Panel, Table, TableSkeleton, Tabs, Td, Th } from "@/components/ui/layout";
import { ApiError, api, type Schemas } from "@/lib/api/client";
import { P, useCan } from "@/lib/shop-context";

type Kind = "categories" | "brands" | "units";

type Editing = { id?: number; name: string; parentId: string; active: boolean; decimalsAllowed: boolean };

/** Categories (a tree), brands and units (CAT-03). Used items are switched off, never deleted. */
export default function CataloguePage() {
  const can = useCan();
  const client = useQueryClient();
  const [tab, setTab] = React.useState<Kind>("categories");
  const [editing, setEditing] = React.useState<Editing | null>(null);
  const [error, setError] = React.useState<ApiError | null>(null);

  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Schemas["CategoryView"][]>("/categories") });
  const brands = useQuery({ queryKey: ["brands"], queryFn: () => api<Schemas["BrandView"][]>("/brands") });
  const units = useQuery({ queryKey: ["units"], queryFn: () => api<Schemas["UnitView"][]>("/units") });

  const save = useMutation({
    mutationFn: (e: Editing) => {
      const body =
        tab === "categories"
          ? { name: e.name, parentId: e.parentId ? Number(e.parentId) : e.id ? 0 : undefined, active: e.active }
          : tab === "units"
            ? { name: e.name, active: e.active, decimalsAllowed: e.decimalsAllowed }
            : { name: e.name, active: e.active };
      return e.id ? api(`/${tab}/${e.id}`, { method: "PATCH", body }) : api(`/${tab}`, { method: "POST", body });
    },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: [tab] });
      setEditing(null);
      setError(null);
      toast.success("Saved");
    },
    onError: (e) => setError(e instanceof ApiError ? e : null),
  });

  const manage = can(P.CATALOG_MANAGE);
  const names = Object.fromEntries((categories.data ?? []).map((c) => [c.id, c.name]));
  const sortedCategories = [...(categories.data ?? [])].sort((a, b) =>
    (a.parentId ? names[a.parentId] + " › " + a.name : a.name).localeCompare(b.parentId ? names[b.parentId] + " › " + b.name : b.name),
  );

  return (
    <>
      <PageHeader
        title="Categories, brands and units"
        actions={
          manage && (
            <Button onClick={() => setEditing({ name: "", parentId: "", active: true, decimalsAllowed: false })}>
              <Plus /> New {tab === "categories" ? "category" : tab === "brands" ? "brand" : "unit"}
            </Button>
          )
        }
      />
      <Tabs
        value={tab}
        onChange={(t) => {
          setTab(t);
          setEditing(null);
        }}
        tabs={[
          { value: "categories", label: "Categories" },
          { value: "brands", label: "Brands" },
          { value: "units", label: "Units" },
        ]}
      />
      <Panel padded={false}>
        {tab === "categories" &&
          (categories.isPending ? (
            <TableSkeleton />
          ) : sortedCategories.length === 0 ? (
            <Empty title="No categories" />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Category</Th>
                  <Th align="right">Products</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {sortedCategories.map((c) => (
                  <Row key={c.id} onEdit={manage ? () => setEditing({ id: c.id, name: c.name, parentId: c.parentId ? String(c.parentId) : "", active: c.active, decimalsAllowed: false }) : undefined}>
                    <Td>
                      {c.parentId && <span className="text-faint">{names[c.parentId]} › </span>}
                      {c.name}
                    </Td>
                    <Td align="right">{c.productCount}</Td>
                    <Td>{c.active ? <Badge tone="good">Active</Badge> : <Badge>Off</Badge>}</Td>
                  </Row>
                ))}
              </tbody>
            </Table>
          ))}
        {tab === "brands" &&
          (brands.isPending ? (
            <TableSkeleton />
          ) : (brands.data ?? []).length === 0 ? (
            <Empty title="No brands yet" description="Brands are also created by importing products." />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Brand / company</Th>
                  <Th align="right">Products</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {brands.data!.map((b) => (
                  <Row key={b.id} onEdit={manage ? () => setEditing({ id: b.id, name: b.name, parentId: "", active: b.active, decimalsAllowed: false }) : undefined}>
                    <Td>{b.name}</Td>
                    <Td align="right">{b.productCount}</Td>
                    <Td>{b.active ? <Badge tone="good">Active</Badge> : <Badge>Off</Badge>}</Td>
                  </Row>
                ))}
              </tbody>
            </Table>
          ))}
        {tab === "units" &&
          (units.isPending ? (
            <TableSkeleton />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Unit</Th>
                  <Th>Decimals (e.g. 1.25 kg)</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {(units.data ?? []).map((u) => (
                  <Row key={u.id} onEdit={manage ? () => setEditing({ id: u.id, name: u.name, parentId: "", active: u.active, decimalsAllowed: u.decimalsAllowed }) : undefined}>
                    <Td>{u.name}</Td>
                    <Td>{u.decimalsAllowed ? "Allowed" : "Whole numbers"}</Td>
                    <Td>{u.active ? <Badge tone="good">Active</Badge> : <Badge>Off</Badge>}</Td>
                  </Row>
                ))}
              </tbody>
            </Table>
          ))}
      </Panel>

      {editing && (
        <Dialog
          open
          onOpenChange={(o) => !o && setEditing(null)}
          title={(editing.id ? "Edit " : "New ") + (tab === "categories" ? "category" : tab === "brands" ? "brand" : "unit")}
          footer={
            <>
              <Button variant="ghost" onClick={() => setEditing(null)}>
                Cancel
              </Button>
              <Button onClick={() => save.mutate(editing)} loading={save.isPending}>
                Save
              </Button>
            </>
          }
        >
          <form
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate(editing);
            }}
          >
            <Field label="Name" error={error?.field("name") ?? (error && !error.fieldErrors.length ? error.message : undefined)}>
              <Input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} autoFocus required />
            </Field>
            {tab === "categories" && (
              <Field label="Inside" hint="Leave empty for a main category">
                <Select value={editing.parentId} onChange={(e) => setEditing({ ...editing, parentId: e.target.value })}>
                  <option value="">— Main category —</option>
                  {(categories.data ?? [])
                    .filter((c) => c.id !== editing.id)
                    .map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                </Select>
              </Field>
            )}
            {tab === "units" && (
              <Checkbox label="Allow decimals" hint="For kg, litre, metre…" checked={editing.decimalsAllowed} onChange={(e) => setEditing({ ...editing, decimalsAllowed: e.target.checked })} />
            )}
            {editing.id && <Checkbox label="Active" checked={editing.active} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} />}
          </form>
        </Dialog>
      )}
    </>
  );
}

function Row({ children, onEdit }: { children: React.ReactNode; onEdit?: () => void }) {
  return (
    <tr className={onEdit ? "cursor-pointer hover:bg-raised/40" : undefined} onClick={onEdit} tabIndex={onEdit ? 0 : undefined} onKeyDown={(e) => e.key === "Enter" && onEdit?.()}>
      {children}
    </tr>
  );
}
