"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Star } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/fields";
import { Badge, PageHeader, Panel, TableSkeleton } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { label } from "@/lib/format";
import { P, useCan, useRefreshContext } from "@/lib/shop-context";

type Branch = Schemas["BranchView"];
type BranchForm = { id?: number; name: string; code: string; kind: string; address: string; phone: string };

/** Branches, godowns and their tills (SET-02). Plan limits apply (BIL-08). */
export default function BranchesPage() {
  const can = useCan();
  const client = useQueryClient();
  const refresh = useRefreshContext();
  const manage = can(P.BRANCHES_MANAGE);
  const branches = useQuery({ queryKey: ["branches"], queryFn: () => api<Branch[]>("/branches") });
  const tills = useQuery({ queryKey: ["tills"], queryFn: () => api<Schemas["TillView"][]>("/tills") });
  const [editing, setEditing] = React.useState<BranchForm | null>(null);
  const [error, setError] = React.useState<ApiError | null>(null);
  const [newTill, setNewTill] = React.useState<{ branchId: number; name: string } | null>(null);

  const done = (message: string) => {
    client.invalidateQueries({ queryKey: ["branches"] });
    client.invalidateQueries({ queryKey: ["tills"] });
    refresh();
    toast.success(message);
  };
  const saveBranch = useMutation({
    mutationFn: (f: BranchForm) => {
      const body = { name: f.name, code: f.code || undefined, kind: f.kind, address: f.address, phone: f.phone };
      return f.id ? api(`/branches/${f.id}`, { method: "PATCH", body }) : api("/branches", { method: "POST", body });
    },
    onSuccess: () => {
      setEditing(null);
      setError(null);
      done("Branch saved");
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e : null);
      if (!(e instanceof ApiError && e.fieldErrors.length)) toast.error(errorMessage(e));
    },
  });
  const action = useMutation({
    mutationFn: ({ id, what }: { id: number; what: "activate" | "deactivate" | "make-default" }) => api(`/branches/${id}/${what}`, { method: "POST" }),
    onSuccess: () => done("Saved"),
    onError: (e) => toast.error(errorMessage(e)),
  });
  const till = useMutation({
    mutationFn: (t: { id?: number; branchId?: number; name?: string; active?: boolean }) =>
      t.id ? api(`/tills/${t.id}`, { method: "PATCH", body: { name: t.name, active: t.active } }) : api("/tills", { method: "POST", body: t }),
    onSuccess: () => {
      setNewTill(null);
      done("Till saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <PageHeader
        title="Branches and tills"
        description="Every place that holds stock: shop floors, godowns, other branches"
        actions={
          manage && (
            <Button onClick={() => setEditing({ name: "", code: "", kind: "SHOP", address: "", phone: "" })}>
              <Plus /> New branch
            </Button>
          )
        }
      />
      {branches.isPending ? (
        <TableSkeleton />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {branches.data?.map((b) => {
            const own = (tills.data ?? []).filter((t) => t.branchId === b.id);
            return (
              <Panel
                key={b.id}
                title={
                  <span className="flex flex-wrap items-center gap-2">
                    {b.name} <span className="font-mono text-xs text-faint">{b.code}</span>
                    {b.isDefault && (
                      <Badge tone="aqua">
                        <Star className="size-3" /> Main
                      </Badge>
                    )}
                    {!b.active && <Badge tone="bad">Closed</Badge>}
                  </span>
                }
                description={[label(b.kind), b.address, b.phone].filter(Boolean).join(" · ")}
                actions={
                  manage && (
                    <Button size="sm" variant="ghost" onClick={() => setEditing({ id: b.id, name: b.name, code: b.code, kind: b.kind, address: b.address ?? "", phone: b.phone ?? "" })}>
                      Edit
                    </Button>
                  )
                }
              >
                <p className="mb-2 text-xs font-semibold text-faint uppercase">Tills</p>
                {own.length === 0 ? (
                  <p className="text-sm text-muted">No tills (godowns usually have none).</p>
                ) : (
                  <ul className="space-y-1">
                    {own.map((t) => (
                      <li key={t.id} className="flex items-center justify-between gap-2 text-sm">
                        <span className={t.active ? "" : "text-faint line-through"}>{t.name}</span>
                        {manage && (
                          <Button size="sm" variant="ghost" onClick={() => till.mutate({ id: t.id, active: !t.active })}>
                            {t.active ? "Switch off" : "Switch on"}
                          </Button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
                {manage && (
                  <div className="mt-4 flex flex-wrap gap-2 border-t border-line pt-4">
                    {b.active && (
                      <Button size="sm" variant="secondary" onClick={() => setNewTill({ branchId: b.id, name: `Till ${own.length + 1}` })}>
                        <Plus /> Till
                      </Button>
                    )}
                    {!b.isDefault && b.active && (
                      <Button size="sm" variant="secondary" onClick={() => action.mutate({ id: b.id, what: "make-default" })}>
                        Make main branch
                      </Button>
                    )}
                    {!b.isDefault && (
                      <Button size="sm" variant={b.active ? "danger" : "secondary"} onClick={() => action.mutate({ id: b.id, what: b.active ? "deactivate" : "activate" })}>
                        {b.active ? "Close branch" : "Reopen"}
                      </Button>
                    )}
                  </div>
                )}
              </Panel>
            );
          })}
        </div>
      )}

      {editing && (
        <Dialog
          open
          onOpenChange={(o) => !o && setEditing(null)}
          title={editing.id ? "Edit branch" : "New branch"}
          footer={
            <>
              <Button variant="ghost" onClick={() => setEditing(null)}>
                Cancel
              </Button>
              <Button onClick={() => saveBranch.mutate(editing)} loading={saveBranch.isPending}>
                Save
              </Button>
            </>
          }
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name" required error={error?.field("name") ?? (error?.code === "duplicate_name" ? error.message : undefined)} className="sm:col-span-2">
              <Input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} autoFocus />
            </Field>
            <Field label="Short code" hint="e.g. B2" error={error?.field("code") ?? (error?.code === "duplicate_code" ? error.message : undefined)}>
              <Input value={editing.code} onChange={(e) => setEditing({ ...editing, code: e.target.value.toUpperCase() })} className="font-mono" maxLength={10} />
            </Field>
            <Field label="Type">
              <Select value={editing.kind} onChange={(e) => setEditing({ ...editing, kind: e.target.value })}>
                <option value="SHOP">Shop floor (sells)</option>
                <option value="GODOWN">Godown (stores only)</option>
              </Select>
            </Field>
            <Field label="Address" className="sm:col-span-2">
              <Input value={editing.address} onChange={(e) => setEditing({ ...editing, address: e.target.value })} />
            </Field>
            <Field label="Phone">
              <Input value={editing.phone} onChange={(e) => setEditing({ ...editing, phone: e.target.value })} />
            </Field>
          </div>
        </Dialog>
      )}
      {newTill && (
        <Dialog
          open
          onOpenChange={(o) => !o && setNewTill(null)}
          title="New till"
          footer={
            <Button onClick={() => till.mutate(newTill)} loading={till.isPending}>
              Add till
            </Button>
          }
        >
          <Field label="Till name">
            <Input value={newTill.name} onChange={(e) => setNewTill({ ...newTill, name: e.target.value })} autoFocus />
          </Field>
        </Dialog>
      )}
    </>
  );
}
