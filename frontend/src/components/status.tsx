import { Badge } from "@/components/ui/layout";
import { label } from "@/lib/format";

const TONES: Record<string, "good" | "warn" | "bad" | "info" | "aqua" | "neutral"> = {
  COMPLETED: "good",
  RECEIVED: "good",
  POSTED: "good",
  PAID: "good",
  CLOSED: "neutral",
  REPAIRED: "good",
  REPLACED: "good",
  IN_STOCK: "good",
  OPEN: "aqua",
  ORDERED: "aqua",
  DRAFT: "neutral",
  SENT: "info",
  IN_TRANSIT: "info",
  PARTIAL: "warn",
  PARTIALLY_RETURNED: "warn",
  CONVERTED: "good",
  EXPIRED: "bad",
  RETURNED: "warn",
  CANCELLED: "bad",
  VOID: "bad",
  REJECTED: "bad",
  SOLD: "neutral",
  LOW: "warn",
  OUT: "bad",
  NEGATIVE: "bad",
  NEAR_EXPIRY: "warn",
  DEAD: "neutral",
  FAILED: "bad",
  RUNNING: "info",
  DONE: "good",
  ACTIVE: "aqua",
  CHECKING: "info",
  WAITING_PARTS: "warn",
  REPAIRING: "info",
  READY: "good",
  DELIVERED: "neutral",
  NOT_REPAIRABLE: "bad",
  PENDING: "aqua",
  ON_THE_WAY: "info",
  INSTALLED: "good",
  INVOICED: "good",
  UPCOMING: "neutral",
  DUE: "warn",
  LATE: "bad",
  PART_PAID: "warn",
  NEW: "aqua",
  PREPARING: "info",
  SERVED: "neutral",
};

/** A document or item status as a coloured badge. */
export function Status({ value }: { value: string | null | undefined }) {
  if (!value) return null;
  return <Badge tone={TONES[value] ?? "neutral"}>{label(value)}</Badge>;
}

/** A sale's state for people: cancelled, returned (all or part) or completed. */
export function saleState(s: { status: string; total: string; returned?: string | null }): string {
  if (s.status === "CANCELLED") return "CANCELLED";
  if (s.returned && s.returned !== "0.00") return s.returned === s.total ? "RETURNED" : "PARTIALLY_RETURNED";
  return "COMPLETED";
}
