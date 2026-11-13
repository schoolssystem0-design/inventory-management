"use client";

import { ShieldCheck } from "lucide-react";
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input } from "@/components/ui/fields";
import { ApiError } from "@/lib/api/client";

type Headers = Record<string, string>;

type Pending = { limit: string; resolve: (headers: Headers | null) => void };

/**
 * Manager approval (ACC-04): run a call; if the server answers 403 approval_required, ask for a manager's username
 * and PIN and send the same call again with X-Approval-User / X-Approval-Pin. A wrong PIN keeps the dialog open.
 */
export function useApproval() {
  const [pending, setPending] = React.useState<Pending | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const run = React.useCallback(async <T,>(call: (headers?: Headers) => Promise<T>): Promise<T> => {
    try {
      return await call();
    }
    catch (first) {
      if (!(first instanceof ApiError) || first.code !== "approval_required") throw first;
      setError(null);
      for (;;) {
        const headers = await new Promise<Headers | null>((resolve) => setPending({ limit: first.message, resolve }));
        if (!headers) {
          setPending(null);
          throw first;
        }
        try {
          const result = await call(headers);
          setPending(null);
          return result;
        }
        catch (again) {
          if (again instanceof ApiError && ["pin_wrong", "pin_locked", "approver_invalid", "approver_not_allowed"].includes(again.code)) {
            setError(again.message);
            continue;
          }
          setPending(null);
          throw again;
        }
      }
    }
  }, []);

  const dialog = pending ? <ApprovalDialog key="approval" pending={pending} error={error} /> : null;
  return { run, dialog };
}

function ApprovalDialog({ pending, error }: { pending: Pending; error: string | null }) {
  const [user, setUser] = React.useState("");
  const [pin, setPin] = React.useState("");
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && pending.resolve(null)}
      title="Manager approval"
      description={pending.limit}
      footer={
        <>
          <Button variant="ghost" onClick={() => pending.resolve(null)}>
            Cancel
          </Button>
          <Button
            type="submit"
            form="approval-form"
            disabled={!user.trim() || pin.length < 4}
          >
            <ShieldCheck /> Approve
          </Button>
        </>
      }
    >
      <form
        id="approval-form"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          pending.resolve({ "X-Approval-User": user.trim(), "X-Approval-Pin": pin });
          setPin("");
        }}
      >
        <Field label="Manager's username">
          <Input value={user} onChange={(e) => setUser(e.target.value)} autoFocus autoComplete="off" />
        </Field>
        <Field label="PIN" error={error ?? undefined}>
          <Input
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
            type="password"
            inputMode="numeric"
            autoComplete="off"
            className="font-mono tracking-[0.4em]"
          />
        </Field>
      </form>
    </Dialog>
  );
}
