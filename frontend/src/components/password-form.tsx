"use client";

import Link from "next/link";
import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api } from "@/lib/api/client";
import { useShopHref } from "@/lib/shop-context";

export const PASSWORD_HINT = "At least 10 characters. A short phrase works well; avoid your username.";

/** Setting a password from a one-time link (account setup or reset). */
export function PasswordForm({
  token,
  endpoint,
  title,
  subtitle,
  done,
}: {
  token: string;
  endpoint: string;
  title: string;
  subtitle: string;
  done: string;
}) {
  const shopHref = useShopHref();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [finished, setFinished] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldError(undefined);
    if (password !== confirm) {
      setFieldError("The two passwords are different");
      return;
    }
    setBusy(true);
    try {
      await api(endpoint, { method: "POST", body: { token, newPassword: password } });
      setFinished(true);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length) setFieldError(err.fieldErrors[0].message);
      else setError(err instanceof Error ? err.message : "Could not save the password");
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthCard title={title}>
        <p className="text-sm text-muted">This link is incomplete. Open the full link from your email or message.</p>
      </AuthCard>
    );
  }
  if (finished) {
    return (
      <AuthCard title="All done">
        <p className="mb-4 text-sm text-muted">{done}</p>
        <Link href={shopHref("/login")} className="font-semibold text-aqua hover:underline">
          Go to sign in →
        </Link>
      </AuthCard>
    );
  }
  return (
    <AuthCard title={title} subtitle={subtitle}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="New password" hint={PASSWORD_HINT} error={fieldError}>
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required autoFocus />
        </Field>
        <Field label="Type it again">
          <Input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
        </Field>
        <FormError message={error} />
        <Button type="submit" className="w-full" loading={busy}>
          Save password
        </Button>
      </form>
    </AuthCard>
  );
}
