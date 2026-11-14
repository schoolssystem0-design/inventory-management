"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AuthCard, FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { ApiError, api, resetCsrfToken, type Schemas } from "@/lib/api/client";
import { BRAND, BRAND_ASSETS } from "@/lib/brand";
import { currentShopCode, shopUrl } from "@/lib/routing";
import { useShopPublicHref } from "@/lib/shop-public-href";
import { useBrowserValue } from "@/lib/use-browser-value";

export default function ShopLoginPage() {
  const router = useRouter();
  const client = useQueryClient();
  const shopHref = useShopPublicHref();
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
      router.replace(shopUrl(code, "/"));
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
      logo={BRAND_ASSETS.logo}
      logoWide
      footer={<span>{BRAND.product}</span>}
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
        <div className="space-y-2 text-center text-sm">
          <p>
            <Link href={shopHref("/forgot-password")} className="font-medium text-aqua hover:underline">
              Forgot or reset password
            </Link>
          </p>
          <p className="text-xs text-faint">
            First time signing in? Use the <strong className="font-normal text-muted">setup link</strong> from your welcome email. If it
            expired, ask the shop owner or {BRAND.company} support for a new link.
          </p>
        </div>
      </form>
    </AuthCard>
  );
}
