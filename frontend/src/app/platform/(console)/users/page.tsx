"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/fields";
import { Badge, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime, label } from "@/lib/format";

const ROLES = ["SUPER_ADMIN", "SUPPORT", "BILLING", "ONBOARDING"];

/** PLT-23: SelectCode staff accounts. Each signs in with an authenticator app. */
export default function PlatformUsersPage() {
  const client = useQueryClient();
  const users = useQuery({ queryKey: ["platform-users"], queryFn: () => api<Schemas["PlatformUserView"][]>("/platform/users") });
  const [form, setForm] = React.useState<{ email: string; name: string; role: string } | null>(null);
  const [secret, setSecret] = React.useState<string | null>(null);
  const refresh = () => client.invalidateQueries({ queryKey: ["platform-users"] });
  const create = useMutation({
    mutationFn: () => api<Schemas["PlatformUserCreated"]>("/platform/users", { method: "POST", body: form! }),
    onSuccess: (r) => {
      setForm(null);
      setSecret(r.temporaryPassword);
      refresh();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Schemas["PlatformUserInput"] }) => api(`/platform/users/${id}`, { method: "PATCH", body }),
    onSuccess: () => {
      refresh();
      toast.success("Saved");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const reset = useMutation({
    mutationFn: (id: number) => api<Schemas["PlatformUserCreated"]>(`/platform/users/${id}/reset`, { method: "POST" }),
    onSuccess: (r) => {
      setSecret(r.temporaryPassword);
      refresh();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader
        title="Platform users"
        actions={
          <Button onClick={() => setForm({ email: "", name: "", role: "SUPPORT" })}>
            <Plus /> New user
          </Button>
        }
      />
      <Panel padded={false}>
        {users.isPending ? (
          <TableSkeleton />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Role</Th>
                <Th>Authenticator</Th>
                <Th>Last sign-in</Th>
                <Th>Status</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {users.data?.map((u) => (
                <tr key={u.id}>
                  <Td>
                    {u.name}
                    <p className="text-xs text-faint">{u.email}</p>
                  </Td>
                  <Td>
                    <Select value={u.role} onChange={(e) => update.mutate({ id: u.id, body: { role: e.target.value } })} className="w-auto py-1" aria-label={`Role of ${u.name}`}>
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {label(r)}
                        </option>
                      ))}
                    </Select>
                  </Td>
                  <Td>{u.totpEnrolled ? <Badge tone="good">Set up</Badge> : <Badge tone="warn">At next sign-in</Badge>}</Td>
                  <Td className="text-muted">{u.lastLoginAt ? dateTime(u.lastLoginAt) : "Never"}</Td>
                  <Td>{u.status === "ACTIVE" ? <Badge tone="good">Active</Badge> : <Badge tone="bad">Off</Badge>}</Td>
                  <Td>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" onClick={() => reset.mutate(u.id)}>
                        Reset
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => update.mutate({ id: u.id, body: { status: u.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" } })}>
                        {u.status === "ACTIVE" ? "Switch off" : "Switch on"}
                      </Button>
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      {form && (
        <Dialog
          open
          onOpenChange={(o) => !o && setForm(null)}
          title="New platform user"
          footer={
            <Button onClick={() => create.mutate()} loading={create.isPending}>
              Create
            </Button>
          }
        >
          <div className="space-y-4">
            <Field label="Name">
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
            </Field>
            <Field label="Email">
              <Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </Field>
            <Field label="Role">
              <Select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {label(r)}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
        </Dialog>
      )}
      {secret && (
        <Dialog
          open
          onOpenChange={(o) => !o && setSecret(null)}
          title="Temporary password"
          description="Shown only now. They sign in with it and set up their authenticator app."
          footer={<Button onClick={() => setSecret(null)}>Done</Button>}
        >
          <p className="rounded-xl bg-deep p-4 text-center font-mono text-xl tracking-wider text-aqua select-all">{secret}</p>
        </Dialog>
      )}
    </>
  );
}
