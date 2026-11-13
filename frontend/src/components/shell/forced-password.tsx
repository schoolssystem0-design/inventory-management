"use client";

import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { PASSWORD_HINT } from "@/components/password-form";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api } from "@/lib/api/client";

/** Shown when the user signed in with a temporary password: nothing else works until it is changed. */
export function ForcedPasswordChange({ onDone }: { onDone: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setMismatch(next !== confirm);
    if (next !== confirm) return;
    setBusy(true);
    try {
      await api("/auth/change-password", { method: "POST", body: { currentPassword: current, newPassword: next } });
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "error", "Could not change the password"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Choose your own password" subtitle="You signed in with a temporary password">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Temporary password" error={error?.field("currentPassword")}>
          <Input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required autoFocus />
        </Field>
        <Field label="New password" hint={PASSWORD_HINT} error={error?.field("newPassword")}>
          <Input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" required />
        </Field>
        <Field label="Type it again" error={mismatch ? "The two passwords are different" : undefined}>
          <Input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
        </Field>
        <FormError message={error && !error.fieldErrors.length ? error.message : null} />
        <Button type="submit" className="w-full" loading={busy}>
          Save and continue
        </Button>
      </form>
    </AuthCard>
  );
}
