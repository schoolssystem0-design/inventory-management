"use client";

import { useState } from "react";
import { BrandMark } from "@/components/brand-mark";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/fields";
import { BRAND } from "@/lib/brand";
import { platformUrl, shopUrl } from "@/lib/routing";
import { useBrowserValue } from "@/lib/use-browser-value";

/** The bare domain: staff type their shop code and are sent to their shop's address. */
export default function LandingPage() {
  const [code, setCode] = useState("");
  const platformHref = useBrowserValue(() => platformUrl("/login"), "/login");
  const clean = code.trim().toLowerCase();
  const valid = /^[a-z0-9]([a-z0-9-]{0,28}[a-z0-9])?$/.test(clean) && clean.length >= 2;
  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <div className="glass w-full max-w-sm p-6">
        <div className="mb-6">
          <BrandMark variant="logo" className="mb-3 max-h-11" />
          <p className="text-sm text-muted">Go to your shop</p>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (valid) window.location.href = shopUrl(clean, "/login");
          }}
          className="space-y-4"
        >
          <Field label="Shop code" hint="It is the first part of your shop's address, e.g. alimobiles">
            <Input value={code} onChange={(e) => setCode(e.target.value)} autoFocus autoCapitalize="none" autoComplete="off" />
          </Field>
          <Button type="submit" className="w-full" disabled={!valid}>
            Continue
          </Button>
        </form>
        <p className="mt-6 text-center text-xs text-faint">
          {BRAND.company} staff:{" "}
          <a className="text-aqua hover:underline" href={platformHref}>
            platform sign-in
          </a>
        </p>
      </div>
    </main>
  );
}
