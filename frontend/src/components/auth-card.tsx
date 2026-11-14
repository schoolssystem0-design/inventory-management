import { Store } from "lucide-react";
import * as React from "react";

/** The centred card used by sign-in, setup and reset pages. */
export function AuthCard({
  title,
  subtitle,
  logo,
  logoWide,
  children,
  footer,
}: {
  title: string;
  subtitle?: React.ReactNode;
  logo?: string | null;
  /** Horizontal brand mark (platform login); shop logos stay square. */
  logoWide?: boolean;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="glass p-6">
          <div className="mb-6 flex items-center gap-3">
            {logo ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={logo}
                alt=""
                className={
                  logoWide
                    ? "h-11 w-auto max-w-[11rem] shrink-0 object-contain object-left"
                    : "size-11 shrink-0 rounded-xl object-cover"
                }
              />
            ) : (
              <span className="grid size-11 place-items-center rounded-xl bg-aqua/15 text-aqua">
                <Store className="size-6" aria-hidden />
              </span>
            )}
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold">{title}</h1>
              {subtitle && <p className="text-sm text-muted">{subtitle}</p>}
            </div>
          </div>
          {children}
        </div>
        {footer && <div className="mt-4 text-center text-xs text-faint">{footer}</div>}
      </div>
    </main>
  );
}

export function FormError({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <p role="alert" className="rounded-lg bg-bad/10 px-3 py-2 text-sm text-bad">
      {message}
    </p>
  );
}
