"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Search } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Checkbox, Field, Input, Select } from "@/components/ui/fields";
import { Badge, Empty, Pager, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime } from "@/lib/format";

type User = Schemas["UserView"];
type Form = {
  id: number;
  username: string;
  displayName: string;
  phone: string;
  email: string;
  roleId: string;
  allBranches: boolean;
  branchIds: number[];
  owner: boolean;
};

/** Platform staff: view and edit shop sign-in users without opening support mode (PLT-13). */
export function ShopUsersTab({ shopId, canManage }: { shopId: number; canManage: boolean }) {
  const client = useQueryClient();
  const [q, setQ] = React.useState("");
  const [status, setStatus] = React.useState("");
  const [page, setPage] = React.useState(0);
  const [form, setForm] = React.useState<Form | null>(null);
  const [error, setError] = React.useState<ApiError | null>(null);
  const [secret, setSecret] = React.useState<{ title: string; text: string } | null>(null);
  const base = `/platform/shops/${shopId}`;

  const users = useQuery({
    queryKey: ["platform-shop-users", shopId, q, status, page],
    queryFn: () =>
      api<Schemas["PageResponseUserView"]>(
        `${base}/users?page=${page}&size=50&status=${status}&q=${encodeURIComponent(q)}`,
      ),
    placeholderData: keepPreviousData,
  });
  const roles = useQuery({
    queryKey: ["platform-shop-roles", shopId],
    queryFn: () => api<Schemas["RoleView"][]>(`${base}/roles`),
  });

  const save = useMutation({
    mutationFn: (f: Form) =>
      api<User>(`${base}/users/${f.id}`, {
        method: "PATCH",
        body: {
          displayName: f.displayName,
          phone: f.phone,
          email: f.email,
          roleId: f.owner ? undefined : Number(f.roleId),
          allBranches: f.allBranches,
          branchIds: f.branchIds,
        },
      }),
    onSuccess: () => {
      setForm(null);
      setError(null);
      client.invalidateQueries({ queryKey: ["platform-shop-users", shopId] });
      client.invalidateQueries({ queryKey: ["platform-shop", String(shopId)] });
      toast.success("User saved");
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e : null);
      if (!(e instanceof ApiError && e.fieldErrors.length)) toast.error(errorMessage(e));
    },
  });

  const reset = useMutation({
    mutationFn: ({ userId, byLink }: { userId: number; byLink: boolean }) =>
      api<Schemas["PasswordReset"]>(`${base}/users/${userId}/reset-password`, { method: "POST", body: { byLink } }),
    onSuccess: (r, v) => {
      if (r.temporaryPassword) {
        setSecret({ title: "New temporary password", text: r.temporaryPassword });
      } else {
        toast.success(r.linkSent ? "Reset link emailed" : "Reset link created (email may not be configured)");
      }
      client.invalidateQueries({ queryKey: ["platform-shop-users", shopId] });
      void v;
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <Panel padded={false}>
        <div className="flex flex-wrap gap-2 border-b border-line p-3">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(0);
              }}
              placeholder="Name, username or phone"
              className="pl-9"
              aria-label="Search users"
            />
          </div>
          <Select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
            className="w-auto"
            aria-label="Status"
          >
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Deactivated</option>
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
                  <Th>Last sign-in</Th>
                  <Th>Status</Th>
                  {canManage && <Th className="w-40">Actions</Th>}
                </tr>
              </thead>
              <tbody>
                {users.data!.items.map((u) => (
                  <tr key={u.id} className="hover:bg-raised/30">
                    <Td>
                      <p className="font-medium">{u.displayName}</p>
                      <p className="font-mono text-xs text-faint">{u.username}</p>
                    </Td>
                    <Td>{u.owner ? <Badge tone="aqua">Owner</Badge> : u.roleName}</Td>
                    <Td className="text-muted">{u.lastLoginAt ? dateTime(u.lastLoginAt) : "Never"}</Td>
                    <Td>
                      <Badge tone={u.status === "ACTIVE" ? "good" : "bad"}>{u.status === "ACTIVE" ? "Active" : "Off"}</Badge>
                    </Td>
                    {canManage && (
                      <Td>
                        <div className="flex flex-wrap gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() =>
                              setForm({
                                id: u.id,
                                username: u.username,
                                displayName: u.displayName,
                                phone: u.phone ?? "",
                                email: u.email ?? "",
                                roleId: String(u.roleId),
                                allBranches: u.allBranches,
                                branchIds: u.branchIds,
                                owner: u.owner,
                              })
                            }
                          >
                            Edit
                          </Button>
                        </div>
                      </Td>
                    )}
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
          title={`Edit ${form.displayName}`}
          footer={
            <>
              <Button variant="ghost" onClick={() => reset.mutate({ userId: form.id, byLink: false })} loading={reset.isPending}>
                <KeyRound /> Temporary password
              </Button>
              {form.email && (
                <Button variant="ghost" onClick={() => reset.mutate({ userId: form.id, byLink: true })} loading={reset.isPending}>
                  Email reset link
                </Button>
              )}
              <Button onClick={() => save.mutate(form)} loading={save.isPending}>
                Save profile
              </Button>
            </>
          }
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Full name" required error={error?.field("displayName")}>
              <Input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} autoFocus />
            </Field>
            <Field label="Username" hint="Cannot be changed">
              <Input value={form.username} disabled className="font-mono" />
            </Field>
            <Field label="Phone" error={error?.field("phone")}>
              <Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
            </Field>
            <Field label="Email" hint="For setup and password reset links" error={error?.field("email")}>
              <Input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} type="email" autoCapitalize="none" />
            </Field>
            {!form.owner && (
              <Field label="Role" className="sm:col-span-2" error={error?.field("roleId")}>
                <Select value={form.roleId} onChange={(e) => setForm({ ...form, roleId: e.target.value })}>
                  {(roles.data ?? [])
                    .filter((r) => r.systemKey !== "OWNER")
                    .map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.name}
                      </option>
                    ))}
                </Select>
              </Field>
            )}
            {!form.owner && (
              <Field label="Branches" className="sm:col-span-2">
                <Checkbox
                  label="Can use every branch"
                  checked={form.allBranches}
                  onChange={(e) => setForm({ ...form, allBranches: e.target.checked, branchIds: e.target.checked ? [] : form.branchIds })}
                />
              </Field>
            )}
          </div>
        </Dialog>
      )}

      {secret && (
        <Dialog open onOpenChange={(o) => !o && setSecret(null)} title={secret.title} footer={<Button onClick={() => setSecret(null)}>Done</Button>}>
          <p className="mb-2 text-sm text-muted">Copy this now. It is not shown again.</p>
          <Input readOnly value={secret.text} className="font-mono" aria-label="Temporary password" />
        </Dialog>
      )}
    </>
  );
}