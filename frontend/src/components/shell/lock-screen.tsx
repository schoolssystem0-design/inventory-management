"use client";

import { useMutation } from "@tanstack/react-query";
import { Lock } from "lucide-react";
import { useRouter } from "next/navigation";
import * as React from "react";
import { FormError } from "@/components/auth-card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/fields";
import { api, errorMessage, resetCsrfToken } from "@/lib/api/client";
import { useShop } from "@/lib/shop-context";

const KEY = "pos-locked";

function readLocked() {
  try {
    return sessionStorage.getItem(KEY) === "1";
  }
  catch {
    return false;
  }
}

function writeLocked(value: boolean) {
  try {
    if (value) sessionStorage.setItem(KEY, "1");
    else sessionStorage.removeItem(KEY);
  }
  catch {
    // Private mode: the lock still works for this page.
  }
}

/**
 * Locks the screen after the shop's idle minutes (ACC-09) or on request (the lock button, or the "pos:lock" event).
 * Only for users with a PIN; others are signed out by the server's session timeout instead. The lock survives a page
 * reload in the same tab.
 */
export function useIdleLock(idleMinutes: number, enabled: boolean) {
  const [locked, setLocked] = React.useState(false);

  React.useEffect(() => {
    if (!enabled) return;
    const lockNow = () => {
      writeLocked(true);
      setLocked(true);
    };
    const restore = window.setTimeout(() => {
      if (readLocked()) setLocked(true);
    }, 0);
    let timer = window.setTimeout(lockNow, Math.max(1, idleMinutes) * 60_000);
    const reset = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(lockNow, Math.max(1, idleMinutes) * 60_000);
    };
    const events = ["pointerdown", "keydown", "wheel", "touchstart"] as const;
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }));
    window.addEventListener("pos:lock", lockNow);
    return () => {
      window.clearTimeout(timer);
      window.clearTimeout(restore);
      events.forEach((e) => window.removeEventListener(e, reset));
      window.removeEventListener("pos:lock", lockNow);
    };
  }, [enabled, idleMinutes]);

  const unlock = React.useCallback(() => {
    writeLocked(false);
    setLocked(false);
  }, []);
  return { locked, unlock };
}

/** Full-screen lock: the same user unlocks with their PIN, or signs out. */
export function LockScreen({ onUnlocked }: { onUnlocked: () => void }) {
  const shop = useShop();
  const router = useRouter();
  const [pin, setPin] = React.useState("");
  const unlock = useMutation({
    mutationFn: () => api("/auth/unlock", { method: "POST", body: { pin } }),
    onSuccess: () => {
      setPin("");
      onUnlocked();
    },
    onError: () => setPin(""),
  });
  async function signOut() {
    try {
      await api("/auth/logout", { method: "POST" });
    }
    catch {
      // Signed out anyway.
    }
    resetCsrfToken();
    writeLocked(false);
    router.replace("/login");
  }
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Screen locked"
      className="no-print fixed inset-0 z-[60] flex items-center justify-center bg-page/95 p-4 backdrop-blur"
    >
      <form
        className="glass w-full max-w-sm p-6 text-center"
        onSubmit={(e) => {
          e.preventDefault();
          if (pin.length >= 4) unlock.mutate();
        }}
      >
        <span className="mx-auto grid size-12 place-items-center rounded-full bg-aqua/15 text-aqua">
          <Lock className="size-6" aria-hidden />
        </span>
        <h1 className="mt-4 text-lg font-semibold">Screen locked</h1>
        <p className="mt-1 text-sm text-muted">{shop.user.displayName} · enter your PIN to continue</p>
        <Input
          autoFocus
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
          inputMode="numeric"
          type="password"
          autoComplete="off"
          aria-label="PIN"
          className="mt-5 text-center font-mono text-2xl tracking-[0.5em]"
        />
        <div className="mt-3">
          <FormError message={unlock.error ? errorMessage(unlock.error) : null} />
        </div>
        <Button type="submit" className="mt-4 w-full" loading={unlock.isPending} disabled={pin.length < 4}>
          Unlock
        </Button>
        <button type="button" onClick={signOut} className="mt-3 text-sm text-faint hover:text-ink">
          Sign out instead
        </button>
      </form>
    </div>
  );
}
