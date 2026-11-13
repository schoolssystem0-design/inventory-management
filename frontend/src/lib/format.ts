/**
 * Display formatting (docs/architecture/CODING-STANDARDS.md §3). The API sends money and quantities as strings ("1250.50"); they
 * are never turned into JS numbers for arithmetic, only for display.
 */

const GROUP = /\B(?=(\d{3})+(?!\d))/g;

/** "1250.5" → "Rs 1,250.50"; null → "—". */
export function money(value: string | number | null | undefined, withCurrency = true): string {
  if (value === null || value === undefined || value === "") return "—";
  const text = typeof value === "number" ? value.toFixed(2) : value;
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = (negative ? text.slice(1) : text).split(".");
  const grouped = whole.replace(GROUP, ",") + "." + (fraction + "00").slice(0, 2);
  return `${negative ? "-" : ""}${withCurrency ? "Rs " : ""}${grouped}`;
}

/** "12.500" → "12.5", "3.000" → "3". */
export function qty(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const text = typeof value === "number" ? String(value) : value;
  if (!text.includes(".")) return text.replace(GROUP, ",");
  const [whole, fraction] = text.split(".");
  const trimmed = fraction.replace(/0+$/, "");
  return whole.replace(GROUP, ",") + (trimmed ? "." + trimmed : "");
}

/** Accepts what people type ("1,250", " 99.5 ") and returns the API string, or null if it is not a valid amount. */
export function parseAmount(input: string, decimals = 2): string | null {
  const clean = input.replace(/,/g, "").trim();
  if (clean === "") return null;
  const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
  return pattern.test(clean) ? clean : null;
}

const DATE = new Intl.DateTimeFormat("en-PK", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Karachi" });
const DATE_TIME = new Intl.DateTimeFormat("en-PK", {
  day: "numeric",
  month: "short",
  hour: "numeric",
  minute: "2-digit",
  timeZone: "Asia/Karachi",
});

export function date(value: string | null | undefined): string {
  if (!value) return "—";
  const d = value.length === 10 ? new Date(value + "T00:00:00+05:00") : new Date(value);
  return Number.isNaN(d.getTime()) ? value : DATE.format(d);
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : DATE_TIME.format(d);
}

/** "3 products", "1 product". */
export function plural(count: number, word: string, many = word + "s"): string {
  return `${count.toLocaleString("en-PK")} ${count === 1 ? word : many}`;
}

/** Human labels for API enum values. */
export const LABELS: Record<string, string> = {
  TRIAL: "Trial",
  ACTIVE: "Active",
  GRACE: "Grace",
  READ_ONLY: "Read-only",
  SUSPENDED: "Suspended",
  CLOSED: "Closed",
  INACTIVE: "Inactive",
  SUPER_ADMIN: "Super admin",
  SUPPORT: "Support",
  BILLING: "Billing",
  ONBOARDING: "Onboarding",
  MONTH: "Monthly",
  YEAR: "Yearly",
  NONE: "No tax",
  INCLUSIVE: "Tax included in prices",
  EXCLUSIVE: "Tax added on top",
  SHOP: "Shop floor",
  GODOWN: "Godown",
  STANDARD: "Standard",
  SERIAL: "IMEI / serial",
  BATCH: "Batches and expiry",
  VARIANT_PARENT: "Sizes / colours",
  BUNDLE: "Bundle / set",
  SERVICE: "Service",
  RECIPE: "Menu item (from a recipe)",
  DINE_IN: "Dine-in",
  TAKEAWAY: "Takeaway",
  PREPARING: "Preparing",
  SERVED: "Served",
  MM80: "80 mm thermal",
  MM58: "58 mm thermal",
  A4: "A4 page",
  EN: "English",
  UR: "Urdu",
  BOTH: "English and Urdu",
  COMPLETED: "Completed",
  PARTIALLY_RETURNED: "Part returned",
  RETURNED: "Returned",
  CANCELLED: "Cancelled",
  ORDERED: "Ordered",
  PARTIAL: "Part received",
  RECEIVED: "Received",
  IN_TRANSIT: "In transit",
  POSTED: "Posted",
  IN_STOCK: "In stock",
  SOLD: "Sold",
  OWN_USE: "Own use",
  NEAR_EXPIRY: "Near expiry",
  DEAD: "Not selling",
  LOW: "Low stock",
  OUT: "Out of stock",
  JAZZCASH: "JazzCash",
  EASYPAISA: "Easypaisa",
  CREDIT_NOTE: "Credit note",
  ACCOUNT: "On account",
  BOX_OPEN: "Box open",
  NON_PTA: "Non-PTA",
  APPROVED: "PTA approved",
  // Stage 4b modules
  CHECKING: "Checking",
  WAITING_PARTS: "Waiting for parts",
  REPAIRING: "Repairing",
  READY: "Ready",
  DELIVERED: "Delivered",
  NOT_REPAIRABLE: "Can't repair",
  PENDING: "Pending",
  ON_THE_WAY: "On the way",
  INSTALLED: "Installed",
  INVOICED: "Billed",
  UPCOMING: "Upcoming",
  DUE: "Due",
  LATE: "Late",
  PART_PAID: "Part paid",
  PERCENT: "Percent off",
  AMOUNT: "Amount off each",
  BUY_GET: "Buy and get free",
  MULTI_PRICE: "Several for a price",
  BILL_PERCENT: "Percent off the bill",
  BILL_AMOUNT: "Amount off the bill",
  POINTS: "Loyalty points",
  ADVANCE: "Advance",
  EXCHANGE: "Exchange",
  OTC: "Over the counter",
  RX: "Prescription (Rx)",
  CONTROLLED: "Controlled",
  EARN: "Earned",
  REDEEM: "Used",
  TAKE_BACK: "Taken back",
  GIVE_BACK: "Given back",
  ADJUST: "Changed by hand",
  WEIGHT: "Weight (grams)",
  PRICE: "Price (rupees)",
};

export function label(value: string | null | undefined): string {
  if (!value) return "—";
  return LABELS[value] ?? value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, " ");
}
