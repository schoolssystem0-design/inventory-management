"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api, fileUrl, resetCsrfToken, type Schemas } from "@/lib/api/client";
import { currentShopCode } from "@/lib/routing";
import { useBrowserValue } from "@/lib/use-browser-value";

export default function ShopLoginPage() {
  const router = useRouter();
  const client = useQueryClient();
  const code = useBrowserValue(currentShopCode, null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [totp, setTotp] = useState("");
  const [needTotp, setNeedTotp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const shop = useQuery({
    queryKey: ["public-shop", code],
    queryFn: () => api<Schemas["PublicShop"]>(`/public/shops/${code}`),
    enabled: !!code,
    retry: false,
  });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!code) return;
    setBusy(true);
    setError(null);
    try {
      await api("/auth/login", {
        method: "POST",
        body: { shopCode: code, username: username.trim(), password, totpCode: needTotp ? totp.trim() : undefined },
      });
      resetCsrfToken();
      await client.invalidateQueries();
      router.replace("/");
    } catch (err) {
      if (err instanceof ApiError && err.code === "totp_required") {
        setNeedTotp(true);
        setError(null);
      } else {
        setError(err instanceof Error ? err.message : "Could not sign in");
      }
    } finally {
      setBusy(false);
    }
  }

  if (shop.isError) {
    return (
      <AuthCard title="Shop not found" subtitle={code ?? undefined}>
        <p className="text-sm text-muted">This address does not belong to a shop. Check the link you were given.</p>
      </AuthCard>
    );
  }

  const blocked = shop.data && (shop.data.status === "SUSPENDED" || shop.data.status === "CLOSED");
  return (
    <AuthCard
      title={shop.data?.name ?? "Sign in"}
      subtitle="Sign in to your shop"
      logo={fileUrl(shop.data?.logoFileId)}
      footer={<span>SelectCode POS</span>}
    >
      {shop.data?.message && (
        <p className={`mb-4 rounded-lg px-3 py-2 text-sm ${blocked ? "bg-bad/10 text-bad" : "bg-warn/10 text-warn"}`}>
          {shop.data.message}
        </p>
      )}
      <form onSubmit={submit} className="space-y-4">
        <Field label="Username">
          <Input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            autoFocus
            required
            disabled={!!blocked}
          />
        </Field>
        <Field label="Password">
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
            disabled={!!blocked}
          />
        </Field>
        {needTotp && (
          <Field label="Authenticator code" hint="The 6-digit code from your authenticator app">
            <Input
              value={totp}
              onChange={(e) => setTotp(e.target.value.replace(/\D/g, "").slice(0, 6))}
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              className="font-mono tracking-[0.3em]"
            />
          </Field>
        )}
        <FormError message={error} />
        <Button type="submit" className="w-full" loading={busy} disabled={!!blocked}>
          Sign in
        </Button>
        <p className="text-center text-sm">
          <Link href="/forgot-password" className="text-muted hover:text-aqua">
            Forgot password?
          </Link>
        </p>
      </form>
    </AuthCard>
  );
}
