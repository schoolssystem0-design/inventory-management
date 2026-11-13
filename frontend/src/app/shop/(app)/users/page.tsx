"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Plus, Search } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { Checkbox, Field, Input, Select } from "@/components/ui/fields";
import { Badge, Empty, PageHeader, Pager, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime } from "@/lib/format";
import { P, useCan, useRefreshContext, useShop } from "@/lib/shop-context";

type User = Schemas["UserView"];
type Form = {
  id?: number;
  username: string;
  displayName: string;
  phone: string;
  email: string;
  roleId: string;
  allBranches: boolean;
  branchIds: number[];
  sendSetupLink: boolean;
};

/** Users, their role and the branches they may use (ACC-01, ACC-03, ACC-08). */
export default function UsersPage() {
  const can = useCan();
  const shop = useShop();
  const client = useQueryClient();
  const refresh = useRefreshContext();
  const manage = can(P.USERS_MANAGE);
  const [q, setQ] = React.useState("");
  const [status, setStatus] = React.useState("ACTIVE");
  const [page, setPage] = React.useState(0);
  const [form, setForm] = React.useState<Form | null>(null);
  const [error, setError] = React.useState<ApiError | null>(null);
  const [secret, setSecret] = React.useState<{ title: string; text: string } | null>(null);
  const [deactivate, setDeactivate] = React.useState<User | null>(null);

  const users = useQuery({
    queryKey: ["users", q, status, page],
    queryFn: () => api<Schemas["PageResponseUserView"]>(`/users?page=${page}&size=50&status=${status}&q=${encodeURIComponent(q)}`),
    placeholderData: keepPreviousData,
  });
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api<Schemas["RoleView"][]>("/roles") });
  const branches = useQuery({ queryKey: ["branches"], queryFn: () => api<Schemas["BranchView"][]>("/branches") });

  const changed = (message: string) => {
    client.invalidateQueries({ queryKey: ["users"] });
    refresh();
    toast.success(message);
  };
  const save = useMutation({
    mutationFn: async (f: Form) => {
      const body = {
        displayName: f.displayName,
        phone: f.phone,
        email: f.email,
        roleId: Number(f.roleId),
        allBranches: f.allBranches,
        branchIds: f.branchIds,
      };
      if (f.id) return { updated: await api<User>(`/users/${f.id}`, { method: "PATCH", body }) };
      return { created: await api<Schemas["UserCreated"]>("/users", { method: "POST", body: { ...body, username: f.username, sendSetupLink: f.sendSetupLink } }) };
    },
    onSuccess: (r) => {
      setForm(null);
      setError(null);
      changed("User saved");
      if (r.created?.temporaryPassword) {
        setSecret({
          title: `Temporary password for ${r.created.user.username}`,
          text: r.created.temporaryPassword,
        });
      } else if (r.created) {
        toast.info(r.created.setupLinkSent ? "A setup link was emailed" : "The setup link could not be emailed");
      }
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e : null);
      if (!(e instanceof ApiError && e.fieldErrors.length)) toast.error(errorMessage(e));
    },
  });
  const setActive = useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) => api<User>(`/users/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" }),
    onSuccess: (u) => {
      setDeactivate(null);
      changed(u.status === "ACTIVE" ? "User activated" : "User deactivated and signed out");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const reset = useMutation({
    mutationFn: ({ id, byLink }: { id: number; byLink: boolean }) => api<Schemas["PasswordReset"]>(`/users/${id}/reset-password`, { method: "POST", body: { byLink } }),
    onSuccess: (r, v) => {
      setForm(null);
      if (r.temporaryPassword) setSecret({ title: "New temporary password", text: r.temporaryPassword });
      else toast.success(r.linkSent ? "Reset link emailed" : "The email could not be sent");
      client.invalidateQueries({ queryKey: ["users"] });
      void v;
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const assignable = (roles.data ?? []).filter((r) => r.systemKey !== "OWNER");
  const roleDescription = roles.data?.find((r) => String(r.id) === form?.roleId)?.description;

  return (
    <>
      <PageHeader
        title="Users"
        description={`${shop.plan.usage.users} active${shop.plan.limits.users ? ` of ${shop.plan.limits.users} allowed on your plan` : ""}`}
        actions={
          manage && (
            <Button
              onClick={() =>
                setForm({ username: "", displayName: "", phone: "", email: "", roleId: String(assignable.find((r) => r.systemKey === "CASHIER")?.id ?? ""), allBranches: true, branchIds: [], sendSetupLink: false })
              }
            >
              <Plus /> New user
            </Button>
          )
        }
      />
      <Panel padded={false}>
        <div className="flex flex-wrap gap-2 border-b border-line p-3">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Name, username or phone" className="pl-9" aria-label="Search users" />
          </div>
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="w-auto" aria-label="Status">
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Deactivated</option>
            <option value="">All</option>
          </Select>
        </div>
        {users.isPending ? (
          <TableSkeleton />
        ) : (users.data?.items.length ?? 0) === 0 ? (
          <Empty title="No users found" />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>User</Th>
                  <Th>Role</Th>
                  <Th>Branches</Th>
                  <Th>Last sign-in</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {users.data!.items.map((u) => (
                  <tr
                    key={u.id}
                    className={manage && !u.owner ? "cursor-pointer hover:bg-raised/40" : undefined}
                    onClick={() =>
                      manage && !u.owner &&
                      setForm({ id: u.id, username: u.username, displayName: u.displayName, phone: u.phone ?? "", email: u.email ?? "", roleId: String(u.roleId), allBranches: u.allBranches, branchIds: u.branchIds, sendSetupLink: false })
                    }
                  >
                    <Td>
                      <p className="font-medium">{u.displayName}</p>
                      <p className="font-mono text-xs text-faint">{u.username}</p>
                    </Td>
                    <Td>{u.owner ? <Badge tone="aqua">Owner</Badge> : u.roleName}</Td>
                    <Td className="text-muted">
                      {u.allBranches ? "All" : (branches.data ?? []).filter((b) => u.branchIds.includes(b.id)).map((b) => b.name).join(", ")}
                    </Td>
                    <Td className="text-muted">{u.lastLoginAt ? dateTime(u.lastLoginAt) : "Never"}</Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {u.status === "ACTIVE" ? <Badge tone="good">Active</Badge> : <Badge tone="bad">Deactivated</Badge>}
                        {u.locked && <Badge tone="warn">Locked</Badge>}
                        {u.mustChangePassword && <Badge>New password due</Badge>}
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pager page={page} size={50} total={users.data!.total} onPage={setPage} />
          </>
        )}
      </Panel>

      {form && (
        <Dialog
          open
          wide
          onOpenChange={(o) => !o && setForm(null)}
          title={form.id ? `Edit ${form.displayName}` : "New user"}
          footer={
            <>
              {form.id && (
                <>
                  <Button variant="ghost" onClick={() => reset.mutate({ id: form.id!, byLink: false })} loading={reset.isPending}>
                    <KeyRound /> Temporary password
                  </Button>
                  {form.email && (
                    <Button variant="ghost" onClick={() => reset.mutate({ id: form.id!, byLink: true })}>
                      Email reset link
                    </Button>
                  )}
                  <Button
                    variant="danger"
                    onClick={() => {
                      const u = users.data?.items.find((x) => x.id === form.id);
                      if (!u) return;
                      if (u.status === "ACTIVE") setDeactivate(u);
                      else setActive.mutate({ id: u.id, active: true });
                      setForm(null);
                    }}
                  >
                    {users.data?.items.find((x) => x.id === form.id)?.status === "ACTIVE" ? "Deactivate" : "Activate"}
                  </Button>
                </>
              )}
              <Button onClick={() => save.mutate(form)} loading={save.isPending}>
                Save
              </Button>
            </>
          }
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Full name" required error={error?.field("displayName")}>
              <Input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} autoFocus />
            </Field>
            <Field label="Username" hint={form.id ? "Cannot be changed" : "Small letters and numbers, used to sign in"} error={error?.field("username") ?? (error?.code === "duplicate_username" ? error.message : undefined)}>
              <Input value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value.toLowerCase() })} disabled={!!form.id} autoCapitalize="none" className="font-mono" />
            </Field>
            <Field label="Phone" error={error?.field("phone")}>
              <Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} inputMode="tel" />
            </Field>
            <Field label="Email" hint="For setup and password reset links" error={error?.field("email")}>
              <Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </Field>
            <Field label="Role" required hint={roleDescription} error={error?.field("roleId")} className="sm:col-span-2">
              <Select value={form.roleId} onChange={(e) => setForm({ ...form, roleId: e.target.value })}>
                {assignable.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            </Field>
            <div className="sm:col-span-2">
              <Checkbox label="Can use every branch" checked={form.allBranches} onChange={(e) => setForm({ ...form, allBranches: e.target.checked })} />
              {!form.allBranches && (
                <div className="mt-2 grid gap-1 rounded-xl bg-deep p-3 sm:grid-cols-2">
                  {(branches.data ?? []).filter((b) => b.active).map((b) => (
                    <Checkbox
                      key={b.id}
                      label={b.name}
                      checked={form.branchIds.includes(b.id)}
                      onChange={(e) => setForm({ ...form, branchIds: e.target.checked ? [...form.branchIds, b.id] : form.branchIds.filter((x) => x !== b.id) })}
                    />
                  ))}
                  {error?.field("branchIds") && <p className="text-xs text-bad sm:col-span-2">{error.field("branchIds")}</p>}
                </div>
              )}
            </div>
            {!form.id && (
              <Checkbox
                className="sm:col-span-2"
                label="Email them a setup link instead of a temporary password"
                hint="They choose their own password; the link works for 48 hours"
                checked={form.sendSetupLink}
                onChange={(e) => setForm({ ...form, sendSetupLink: e.target.checked })}
              />
            )}
          </div>
        </Dialog>
      )}

      {secret && (
        <Dialog
          open
          onOpenChange={(o) => !o && setSecret(null)}
          title={secret.title}
          description="Shown only now. Give it to the user privately; they must choose their own password when they sign in."
          footer={<Button onClick={() => setSecret(null)}>Done</Button>}
        >
          <p className="rounded-xl bg-deep p-4 text-center font-mono text-2xl tracking-wider text-aqua select-all">{secret.text}</p>
        </Dialog>
      )}

      <ConfirmDialog
        open={!!deactivate}
        onOpenChange={(o) => !o && setDeactivate(null)}
        title={`Deactivate ${deactivate?.displayName}?`}
        description="They are signed out at once and cannot sign in. Their past work stays in the records."
        confirmLabel="Deactivate"
        danger
        loading={setActive.isPending}
        onConfirm={() => deactivate && setActive.mutate({ id: deactivate.id, active: false })}
      />
    </>
  );
}
