"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import QRCode from "qrcode";
import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api, resetCsrfToken, type Schemas } from "@/lib/api/client";
import { BRAND } from "@/lib/brand";
import { platformUrl } from "@/lib/routing";

type LoginResponse = Schemas["PlatformLoginResponse"];

/** SelectCode staff sign-in: email, password and the authenticator code (PLT-03). First sign-in enrols the app. */
export default function PlatformLoginPage() {
  const router = useRouter();
  const client = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
  const [enrol, setEnrol] = useState<{ secret: string; qr: string } | null>(null);
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
      if (enrol) {
        await api<LoginResponse>("/platform/auth/totp/enroll", { method: "POST", body: { code } });
        await finish();
        return;
      }
      const res = await api<LoginResponse>("/platform/auth/login", {
        method: "POST",
        body: { email: email.trim(), password, totpCode: needCode ? code : undefined },
      });
      if (res.enrolmentRequired && res.otpauthUri && res.secret) {
        setEnrol({ secret: res.secret, qr: await QRCode.toDataURL(res.otpauthUri, { margin: 1, width: 220 }) });
        setCode("");
      } else {
        await finish();
      }
    } catch (err) {
      if (err instanceof ApiError && err.code === "totp_required") {
        setNeedCode(true);
      } else {
        setError(err instanceof Error ? err.message : "Could not sign in");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title={BRAND.platformLoginTitle} subtitle="Staff sign-in" footer="Shop staff: use your shop's own address">
      <form onSubmit={submit} className="space-y-4">
        {!enrol ? (
          <>
            <Field label="Email">
              <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required autoFocus />
            </Field>
            <Field label="Password">
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
            </Field>
            {needCode && (
              <Field label="Authenticator code">
                <Input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" autoFocus className="font-mono tracking-[0.3em]" />
              </Field>
            )}
          </>
        ) : (
          <div className="space-y-3">
            <p className="text-sm text-muted">First sign-in: scan this code with an authenticator app, then type the code it shows.</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={enrol.qr} alt="QR code for the authenticator app" width={220} height={220} className="mx-auto rounded-xl bg-white p-2" />
            <p className="font-mono text-xs break-all text-faint">Key: {enrol.secret}</p>
            <Field label="Code from the app">
              <Input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" autoFocus className="font-mono tracking-[0.3em]" />
            </Field>
          </div>
        )}
        <FormError message={error} />
        <Button type="submit" className="w-full" loading={busy}>
          {enrol ? "Finish setup" : "Sign in"}
        </Button>
      </form>
    </AuthCard>
  );
}
