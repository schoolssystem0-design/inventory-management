import { cn } from "cn";
import { AlertTriangle, Inbox } from "lucide-react";
import Link from "next/link";
import * as React from "react";

export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  back?: { href: string; label: string };
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {back && (
          <Link href={back.href} className="mb-1 inline-block text-[13px] text-faint hover:text-aqua">
            ← {back.label}
          </Link>
        )}
        <h1 className="text-2xl font-bold tracking-tight text-ink">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

export function Panel({
  title,
  description,
  actions,
  children,
  className,
  padded = true,
}: {
  title?: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  padded?: boolean;
}) {
  return (
    <section className={cn("glass", className)}>
      {(title || actions) && (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-4">
          <div>
            {title && <h2 className="text-[15px] font-semibold text-ink">{title}</h2>}
            {description && <p className="mt-0.5 text-[13px] text-muted">{description}</p>}
          </div>
          {actions}
        </div>
      )}
      <div className={padded ? "p-5" : undefined}>{children}</div>
    </section>
  );
}

export function StatCard({
  label,
  value,
  hint,
  tone,
  href,
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  tone?: "good" | "warn" | "bad" | "aqua";
  href?: string;
}) {
  const body = (
    <>
      <p className="text-[13px] text-muted">{label}</p>
      <p
        className={cn(
          "mt-1 font-mono text-2xl font-semibold",
          tone === "good" && "text-good",
          tone === "warn" && "text-warn",
          tone === "bad" && "text-bad",
          tone === "aqua" && "text-aqua",
        )}
      >
        {value}
      </p>
      {hint && <p className="mt-1 text-xs text-faint">{hint}</p>}
    </>
  );
  return href ? (
    <Link href={href} className="tile block p-4">
      {body}
    </Link>
  ) : (
    <div className="tile p-4">{body}</div>
  );
}

const BADGE_TONES = {
  neutral: "bg-raised text-muted",
  good: "bg-good/15 text-good",
  warn: "bg-warn/15 text-warn",
  bad: "bg-bad/15 text-bad",
  info: "bg-info/15 text-info",
  aqua: "bg-aqua/15 text-aqua",
};

export function Badge({ tone = "neutral", children, className }: { tone?: keyof typeof BADGE_TONES; children: React.ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium", BADGE_TONES[tone], className)}>
      {children}
    </span>
  );
}

/** Tone for shop and account statuses. */
export function statusTone(status: string | null | undefined): keyof typeof BADGE_TONES {
  switch (status) {
    case "ACTIVE":
      return "good";
    case "TRIAL":
      return "aqua";
    case "GRACE":
      return "warn";
    case "READ_ONLY":
    case "SUSPENDED":
    case "CLOSED":
    case "INACTIVE":
      return "bad";
    default:
      return "neutral";
  }
}

export function Empty({ title, description, action }: { title: string; description?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <Inbox className="size-8 text-faint" aria-hidden />
      <p className="mt-3 font-semibold text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-sm text-muted">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-3 px-6 py-10 text-center">
      <AlertTriangle className="size-7 text-warn" aria-hidden />
      <p className="text-sm text-muted">{message}</p>
      {onRetry && (
        <button className="text-sm font-semibold text-aqua hover:underline" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("skeleton h-4", className)} aria-hidden />;
}

export function TableSkeleton({ rows = 6, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="space-y-3 p-5" role="status" aria-label="Loading">
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="flex gap-4">
          {Array.from({ length: cols }).map((__, c) => (
            <Skeleton key={c} className={c === 0 ? "h-4 flex-[2]" : "h-4 flex-1"} />
          ))}
        </div>
      ))}
    </div>
  );
}

/** A table that scrolls inside its card on narrow screens. */
export function Table({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className="overflow-x-auto">
      <table className={cn("w-full min-w-[560px] text-left text-sm", className)}>{children}</table>
    </div>
  );
}

export function Th({ children, className, align }: { children?: React.ReactNode; className?: string; align?: "right" }) {
  return (
    <th
      scope="col"
      className={cn(
        "border-b border-line px-4 py-2.5 text-xs font-medium tracking-wide text-faint",
        align === "right" && "text-right",
        className,
      )}
    >
      {children}
    </th>
  );
}

export function Td({ children, className, align, title }: { children?: React.ReactNode; className?: string; align?: "right"; title?: string }) {
  return (
    <td title={title} className={cn("border-b border-line px-4 py-3 align-middle", align === "right" && "text-right font-mono", className)}>
      {children}
    </td>
  );
}

export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
}: {
  tabs: { value: T; label: string; hidden?: boolean }[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div role="tablist" className="no-scrollbar mb-5 flex gap-1 overflow-x-auto rounded-xl bg-deep p-1 shadow-[inset_0_0_0_1px_var(--color-line)]">
      {tabs
        .filter((t) => !t.hidden)
        .map((tab) => (
          <button
            key={tab.value}
            role="tab"
            aria-selected={value === tab.value}
            onClick={() => onChange(tab.value)}
            className={cn(
              "shrink-0 rounded-lg px-3.5 py-2 text-[13px] font-semibold transition-colors",
              value === tab.value ? "bg-raised text-ink" : "text-faint hover:text-ink",
            )}
          >
            {tab.label}
          </button>
        ))}
    </div>
  );
}

export function Pager({
  page,
  size,
  total,
  onPage,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total <= size) return null;
  return (
    <div className="flex items-center justify-between gap-3 border-t border-line px-4 py-3 text-[13px] text-muted">
      <span>
        {(page * size + 1).toLocaleString()}–{Math.min(total, (page + 1) * size).toLocaleString()} of {total.toLocaleString()}
      </span>
      <div className="flex gap-1">
        <button className="rounded-lg px-3 py-1.5 hover:bg-raised disabled:opacity-40" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button
          className="rounded-lg px-3 py-1.5 hover:bg-raised disabled:opacity-40"
          disabled={page + 1 >= pages}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}

/** Label/value rows for detail pages. */
export function DetailList({ items }: { items: { label: string; value: React.ReactNode }[] }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
      {items.map((item) => (
        <div key={item.label}>
          <dt className="text-xs text-faint">{item.label}</dt>
          <dd className="mt-0.5 text-sm text-ink">{item.value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}
