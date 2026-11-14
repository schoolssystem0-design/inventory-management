"use client";

import { useMutation } from "@tanstack/react-query";
import QRCode from "qrcode";
import * as React from "react";
import { toast } from "sonner";
import { PASSWORD_HINT } from "@/components/password-form";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { Badge, DetailList, PageHeader, Panel } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { BRAND } from "@/lib/brand";
import { useRefreshContext, useShop } from "@/lib/shop-context";

/** The signed-in user's own account: password, approval PIN, authenticator app (ACC-08..10). */
export default function AccountPage() {
  const shop = useShop();
  const isUser = shop.user.actorType === "USER";
  return (
    <>
      <PageHeader title="My account" />
      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="You">
          <DetailList
            items={[
              { label: "Name", value: shop.user.displayName },
              { label: "Username", value: <span className="font-mono">{shop.user.username}</span> },
              { label: "Role", value: shop.user.roleName },
              { label: "Shop", value: shop.shop.name },
            ]}
          />
        </Panel>
        {isUser ? (
          <>
            <PasswordPanel />
            <PinPanel />
            <TotpPanel />
          </>
        ) : (
          <Panel title="Support session">
            <p className="text-sm text-muted">You are signed in as {BRAND.support()}. Your own account is managed on the platform.</p>
          </Panel>
        )}
      </div>
    </>
  );
}

function PasswordPanel() {
  const [current, setCurrent] = React.useState("");
  const [next, setNext] = React.useState("");
  const [error, setError] = React.useState<ApiError | null>(null);
  const change = useMutation({
    mutationFn: () => api("/auth/change-password", { method: "POST", body: { currentPassword: current, newPassword: next } }),
    onSuccess: () => {
      setCurrent("");
      setNext("");
      setError(null);
      toast.success("Password changed. Your other devices were signed out.");
    },
    onError: (e) => setError(e instanceof ApiError ? e : null),
  });
  return (
    <Panel title="Password">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          change.mutate();
        }}
      >
        <Field label="Current password" error={error?.field("currentPassword")}>
          <Input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
        </Field>
        <Field label="New password" hint={PASSWORD_HINT} error={error?.field("newPassword")}>
          <Input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" required />
        </Field>
        <Button type="submit" loading={change.isPending}>
          Change password
        </Button>
      </form>
    </Panel>
  );
}

function PinPanel() {
  const shop = useShop();
  const refresh = useRefreshContext();
  const [password, setPassword] = React.useState("");
  const [pin, setPin] = React.useState("");
  const [error, setError] = React.useState<ApiError | null>(null);
  const save = useMutation({
    mutationFn: () => api("/auth/pin", { method: "POST", body: { password, pin } }),
    onSuccess: () => {
      setPassword("");
      setPin("");
      setError(null);
      refresh();
      toast.success("PIN saved");
    },
    onError: (e) => setError(e instanceof ApiError ? e : null),
  });
  return (
    <Panel title="Approval PIN" description="Managers type it at the till to approve discounts, returns and write-offs">
      <p className="mb-4">{shop.user.pinSet ? <Badge tone="good">PIN set</Badge> : <Badge tone="warn">No PIN yet</Badge>}</p>
      <form
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="New PIN" hint="4 to 6 digits" error={error?.field("pin")}>
          <Input value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" type="password" autoComplete="off" className="font-mono tracking-widest" />
        </Field>
        <Field label="Your password" error={error?.field("password")}>
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </Field>
        <div>
          <Button type="submit" loading={save.isPending} disabled={pin.length < 4 || !password}>
            Save PIN
          </Button>
        </div>
      </form>
    </Panel>
  );
}

function TotpPanel() {
  const shop = useShop();
  const refresh = useRefreshContext();
  const [enrolment, setEnrolment] = React.useState<Schemas["TotpEnrolment"] | null>(null);
  const [qr, setQr] = React.useState<string | null>(null);
  const [code, setCode] = React.useState("");
  const [password, setPassword] = React.useState("");
  const start = useMutation({
    mutationFn: () => api<Schemas["TotpEnrolment"]>("/auth/totp/start", { method: "POST" }),
    onSuccess: async (e) => {
      setEnrolment(e);
      setQr(await QRCode.toDataURL(e.otpauthUri, { margin: 1, width: 220 }));
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const confirm = useMutation({
    mutationFn: () => api("/auth/totp/confirm", { method: "POST", body: { code } }),
    onSuccess: () => {
      setEnrolment(null);
      setCode("");
      refresh();
      toast.success("Authenticator turned on. Sign-in now asks for a code.");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const disable = useMutation({
    mutationFn: () => api("/auth/totp/disable", { method: "POST", body: { password } }),
    onSuccess: () => {
      setPassword("");
      refresh();
      toast.success("Authenticator turned off");
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Panel title="Authenticator app" description="A second step at sign-in with Google or Microsoft Authenticator">
      {shop.user.totpEnabled ? (
        <div className="space-y-4">
          <Badge tone="good">On</Badge>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="Password to turn it off">
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </Field>
            <Button variant="danger" onClick={() => disable.mutate()} loading={disable.isPending} disabled={!password}>
              Turn off
            </Button>
          </div>
        </div>
      ) : enrolment ? (
        <div className="space-y-4">
          <p className="text-sm text-muted">Scan this with the authenticator app, then type the 6-digit code it shows.</p>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {qr && <img src={qr} alt="QR code for the authenticator app" className="rounded-xl bg-white p-2" width={220} height={220} />}
          <p className="font-mono text-xs break-all text-faint">Or type this key: {enrolment.secret}</p>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="Code from the app">
              <Input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" className="font-mono tracking-[0.3em]" />
            </Field>
            <Button onClick={() => confirm.mutate()} loading={confirm.isPending} disabled={code.length !== 6}>
              Turn on
            </Button>
          </div>
        </div>
      ) : (
        <Button variant="secondary" onClick={() => start.mutate()} loading={start.isPending}>
          Set up the authenticator app
        </Button>
      )}
    </Panel>
  );
}
