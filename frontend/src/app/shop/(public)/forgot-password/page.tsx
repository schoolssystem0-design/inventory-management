"use client";

import Link from "next/link";
import { useState } from "react";
import { AuthCard } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { BRAND } from "@/lib/brand";
import { api } from "@/lib/api/client";
import { currentShopCode } from "@/lib/routing";
import { useShopPublicHref } from "@/lib/shop-public-href";
import { useBrowserValue } from "@/lib/use-browser-value";

export default function ForgotPasswordPage() {
  const shopHref = useShopPublicHref();
  const code = useBrowserValue(currentShopCode, null);
  const [username, setUsername] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api("/auth/password-reset/request", { method: "POST", body: { shopCode: code, username: username.trim() } });
    } finally {
      // Same answer whether or not the account exists.
      setSent(true);
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Reset password" subtitle="We email a one-time link if this account has an email address">
      {sent ? (
        <div className="space-y-4 text-sm text-muted">
          <p>If that username has an email address, a reset link is on its way. It works for 60 minutes.</p>
          <p>No email? Ask the shop owner or {BRAND.company} support for a temporary password or a new setup link.</p>
          <Link href={shopHref("/login")} className="font-semibold text-aqua hover:underline">
            Back to sign in
          </Link>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <Field label="Username">
            <Input value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="none" required autoFocus />
          </Field>
          <Button type="submit" className="w-full" loading={busy}>
            Send reset link
          </Button>
          <p className="text-center text-sm">
            <Link href={shopHref("/login")} className="text-muted hover:text-aqua">
              Back to sign in
            </Link>
          </p>
        </form>
      )}
    </AuthCard>
  );
}
