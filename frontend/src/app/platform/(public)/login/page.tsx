"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api, resetCsrfToken, type Schemas } from "@/lib/api/client";
import { BRAND, BRAND_ASSETS } from "@/lib/brand";
import { platformUrl } from "@/lib/routing";

type LoginResponse = Schemas["PlatformLoginResponse"];

/** Platform staff: email, password, then a one-time code emailed to the user. */
export default function PlatformLoginPage() {
  const router = useRouter();
  const client = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [needEmailCode, setNeedEmailCode] = useState(false);
  const [codeSentHint, setCodeSentHint] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function finish() {
    resetCsrfToken();
    await client.invalidateQueries();
    router.replace(platformUrl("/"));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api<LoginResponse>("/platform/auth/login", {
        method: "POST",
        body: {
          email: email.trim(),
          password,
          emailCode: needEmailCode ? emailCode.trim() : undefined,
        },
      });
      await finish();
    } catch (err) {
      if (err instanceof ApiError && err.code === "email_code_required") {
        setNeedEmailCode(true);
        setCodeSentHint(err.message);
        setError(null);
      } else if (err instanceof ApiError && err.code === "totp_required") {
        setError("This account uses an authenticator app. Contact support to switch to email codes.");
      } else {
        setError(err instanceof Error ? err.message : "Could not sign in");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard
      title={BRAND.platformLoginTitle}
      subtitle="Staff sign-in"
      logo={BRAND_ASSETS.logo}
      logoWide
      footer="Shop staff: use your shop's own address"
    >
      <form onSubmit={submit} className="space-y-4">
        <Field label="Email">
          <Input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
            autoFocus={!needEmailCode}
          />
        </Field>
        <Field label="Password">
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </Field>
        {needEmailCode && (
          <Field
            label="Sign-in code"
            hint={codeSentHint ?? "Check your email for the 6-digit code (10 minutes)."}
          >
            <Input
              value={emailCode}
              onChange={(e) => setEmailCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              className="font-mono tracking-[0.3em]"
            />
          </Field>
        )}
        <FormError message={error} />
        <Button type="submit" className="w-full" loading={busy}>
          {needEmailCode ? "Verify and sign in" : "Sign in"}
        </Button>
      </form>
    </AuthCard>
  );
}