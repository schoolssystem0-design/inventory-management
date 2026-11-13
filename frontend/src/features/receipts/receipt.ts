/**
 * Receipts (POS-09): 80 mm and 58 mm thermal rolls and A4, English, Urdu or both. Built as a small self-contained HTML
 * page and printed from a hidden frame, so the page size, margins and fonts are exactly the receipt's and the app's
 * own screen never prints. The cash drawer opens through the receipt printer ("open drawer on print" in the printer
 * driver), which is how RJ11 drawers work with every common thermal printer.
 */
import type { Schemas } from "@/lib/api/client";
import { date, dateTime, money, qty } from "@/lib/format";
import { addMoney } from "@/lib/money";

export type ReceiptView = Schemas["ReceiptView"];
export type ReceiptSize = "MM80" | "MM58" | "A4";

const esc = (s: string | null | undefined) =>
  (s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

const UR: Record<string, string> = {
  invoice: "رسید",
  item: "آئٹم",
  qty: "تعداد",
  amount: "رقم",
  subtotal: "کل",
  discount: "رعایت",
  tax: "ٹیکس",
  total: "کل رقم",
  paid: "ادا شدہ",
  change: "بقایا واپس",
  due: "باقی",
  savings: "آپ کی بچت",
  thanks: "شکریہ",
  cashier: "کیشیئر",
  customer: "گاہک",
};

function label(lang: string, en: string, key: keyof typeof UR) {
  if (lang === "UR") return `<span class="ur">${UR[key]}</span>`;
  if (lang === "BOTH") return `${en} <span class="ur">${UR[key]}</span>`;
  return en;
}

const METHOD: Record<string, string> = {
  CASH: "Cash",
  CARD: "Card",
  JAZZCASH: "JazzCash",
  EASYPAISA: "Easypaisa",
  BANK: "Bank transfer",
  CREDIT_NOTE: "Credit note",
  ACCOUNT: "On account",
  POINTS: "Loyalty points",
  ADVANCE: "Advance paid",
  EXCHANGE: "Exchange",
  CHEQUE: "Cheque",
};

export function receiptHtml(r: ReceiptView, sizeOverride?: ReceiptSize, copy?: string): string {
  const size = (sizeOverride ?? r.size ?? "MM80") as ReceiptSize;
  const lang = r.language ?? "EN";
  const s = r.sale;
  const width = size === "A4" ? "190mm" : size === "MM58" ? "48mm" : "72mm";
  const page = size === "A4" ? "A4" : `${size === "MM58" ? "58mm" : "80mm"} auto`;
  const small = size === "MM58";
  const L = (en: string, key: keyof typeof UR) => label(lang, en, key);
  const lines = s.lines
    .map((l) => {
      const name = esc(l.productName);
      const meta = [l.serial ? `IMEI ${esc(l.serial)}` : "", l.batchNo ? `Batch ${esc(l.batchNo)}${l.expiry ? " exp " + esc(date(l.expiry)) : ""}` : "", l.warrantyUntil ? `Warranty till ${esc(date(l.warrantyUntil))}` : ""]
        .filter(Boolean)
        .join(" · ");
      const disc = l.discount && l.discount !== "0.00" ? `<div class="muted">Discount −${money(l.discount, false)}</div>` : "";
      if (size === "A4") {
        return `<tr><td>${name}${meta ? `<div class="muted">${meta}</div>` : ""}${disc}</td><td class="r">${qty(l.qty)} ${esc(l.unitName)}</td><td class="r">${money(l.price, false)}</td><td class="r">${money(l.total, false)}</td></tr>`;
      }
      return `<tr><td colspan="2">${name}${meta ? `<div class="muted">${meta}</div>` : ""}</td></tr><tr><td class="muted">${qty(l.qty)} × ${money(l.price, false)}${disc ? " − " + money(l.discount, false) : ""}</td><td class="r">${money(l.total, false)}</td></tr>`;
    })
    .join("");
  const row = (k: string, v: string, strong = false) => `<tr class="${strong ? "big" : ""}"><td>${k}</td><td class="r">${v}</td></tr>`;
  const payments = s.payments
    .filter((p) => p.kind !== "REFUND")
    .map((p) => row(esc(METHOD[p.method] ?? p.method) + (p.reference ? ` <span class="muted">${esc(p.reference)}</span>` : ""), money(p.tendered ?? p.amount, false)))
    .join("");
  const discount = s.lineDiscount !== "0.00" || s.billDiscount !== "0.00";
  const totalDiscount = addMoney(s.lineDiscount, s.billDiscount);
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(s.number)}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Nastaliq+Urdu:wght@400;700&display=swap">
<style>
@page { size: ${page}; margin: ${size === "A4" ? "12mm" : "2mm"}; }
* { box-sizing: border-box; }
body { margin: 0; font: ${small ? "10px" : size === "A4" ? "12px" : "11px"}/1.35 ui-monospace, "Cascadia Mono", Menlo, Consolas, monospace; color: #000; background: #fff; }
.receipt { width: ${width}; margin: 0 auto; }
h1 { font-size: ${small ? "13px" : "16px"}; margin: 0; text-align: center; }
.c { text-align: center; } .r { text-align: right; white-space: nowrap; } .muted { color: #444; font-size: 0.9em; }
.ur { font-family: "Noto Nastaliq Urdu", serif; direction: rtl; line-height: 1.9; }
table { width: 100%; border-collapse: collapse; } td, th { padding: 1px 0; vertical-align: top; }
${size === "A4" ? "th { text-align: left; border-bottom: 1px solid #000; padding: 4px 0; } th.r { text-align: right; } tbody td { padding: 4px 0; border-bottom: 1px solid #ddd; }" : ""}
hr { border: 0; border-top: 1px dashed #000; margin: 4px 0; }
.big td { font-weight: 700; font-size: 1.25em; padding-top: 3px; }
img.logo { display: block; max-width: 40mm; max-height: 18mm; margin: 0 auto 4px; }
</style></head><body><div class="receipt">
${r.logoFileId ? `<img class="logo" src="/api/v1/files/${r.logoFileId}" alt="">` : ""}
<h1>${esc(r.businessName)}</h1>
${r.urduName && lang !== "EN" ? `<div class="c ur" style="font-size:1.2em">${esc(r.urduName)}</div>` : ""}
${r.address ? `<div class="c">${esc(r.address)}</div>` : ""}
${r.phones ? `<div class="c">${esc(r.phones)}</div>` : ""}
${r.ntn ? `<div class="c">NTN ${esc(r.ntn)}${r.strn ? ` · STRN ${esc(r.strn)}` : ""}</div>` : ""}
${r.header ? `<div class="c" style="margin-top:3px">${esc(r.header)}</div>` : ""}
<hr>
<table>
${s.tokenNo ? `<div class="c" style="font-size:22px;font-weight:700">Token #${s.tokenNo}</div><div class="c">${esc(s.orderType === "DINE_IN" ? `Dine-in · Table ${s.tableNo ?? ""}` : s.orderType === "DELIVERY" ? "Delivery" : "Takeaway")}</div>` : ""}
${row(L("Invoice", "invoice"), esc(s.number))}
${row("Date", esc(dateTime(s.at)))}
${row(L("Cashier", "cashier"), esc(s.cashierName))}
${s.customerName ? row(L("Customer", "customer"), esc(s.customerName) + (s.customerPhone ? `<br>${esc(s.customerPhone)}` : "")) : ""}
${s.branchName ? row("Branch", esc(s.branchName)) : ""}
</table>
${copy ? `<div class="c" style="font-weight:700;margin-top:3px">${esc(copy)}</div>` : ""}
${s.status === "CANCELLED" ? `<div class="c" style="font-weight:700;margin-top:3px">CANCELLED</div>` : ""}
<hr>
<table>
${size === "A4" ? `<thead><tr><th>${L("Item", "item")}</th><th class="r">${L("Qty", "qty")}</th><th class="r">Price</th><th class="r">${L("Amount", "amount")}</th></tr></thead>` : ""}
<tbody>${lines}</tbody>
</table>
<hr>
<table>
${row(L("Subtotal", "subtotal"), money(s.subtotal, false))}
${discount ? row(L("Discount", "discount"), "−" + money(totalDiscount, false)) : ""}
${r.showTax && s.tax !== "0.00" ? row(L("Tax", "tax"), money(s.tax, false)) : ""}
${row(L("Total", "total"), money(s.total), true)}
</table>
<hr>
<table>
${payments}
${s.change !== "0.00" ? row(L("Change", "change"), money(s.change, false)) : ""}
${s.due !== "0.00" ? row(L("Due", "due"), money(s.due, false), true) : ""}
${s.promoDiscount && s.promoDiscount !== "0.00" ? row("Offers" + (s.couponCode ? ` (${esc(s.couponCode)})` : ""), "−" + money(s.promoDiscount, false)) : ""}
${s.pointsUsed ? row("Points used", String(s.pointsUsed)) : ""}
${s.pointsEarned ? row("Points earned", String(s.pointsEarned)) : ""}
</table>
${r.showSavings && r.savings && r.savings !== "0.00" ? `<hr><div class="c">${L("You saved", "savings")} ${money(r.savings)}</div>` : ""}
${r.showBarcode ? `<div class="c" style="margin-top:6px;font-size:1.4em;letter-spacing:2px">*${esc(s.number)}*</div>` : ""}
${r.returnPolicy ? `<hr><div class="c muted">${esc(r.returnPolicy)}</div>` : ""}
<div class="c" style="margin-top:6px">${r.footer ? esc(r.footer) : L("Thank you", "thanks")}</div>
<div class="c muted" style="margin-top:4px">SelectCode POS</div>
</div></body></html>`;
}

/** Prints an HTML page from a hidden frame (waits for the logo and the Urdu font). */
export function printHtml(html: string): Promise<void> {
  return new Promise((resolve) => {
    const frame = document.createElement("iframe");
    frame.setAttribute("aria-hidden", "true");
    frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
    document.body.appendChild(frame);
    const done = () => {
      setTimeout(() => frame.remove(), 1000);
      resolve();
    };
    frame.onload = async () => {
      const w = frame.contentWindow;
      if (!w) return done();
      try {
        await Promise.race([w.document.fonts?.ready, new Promise((r) => setTimeout(r, 1500))]);
      }
      catch {
        // Print with the fallback font.
      }
      w.focus();
      w.print();
      done();
    };
    frame.srcdoc = html;
  });
}

/** Z-report (till closing) on the receipt printer. */
export function zReportHtml(z: Schemas["ZReport"], shopName: string, size: ReceiptSize = "MM80"): string {
  const s = z.session;
  const width = size === "A4" ? "120mm" : size === "MM58" ? "48mm" : "72mm";
  const row = (k: string, v: string | null | undefined, strong = false) =>
    `<tr${strong ? ' style="font-weight:700"' : ""}><td>${esc(k)}</td><td style="text-align:right">${v == null ? "—" : esc(v)}</td></tr>`;
  return `<!doctype html><html><head><meta charset="utf-8"><title>Z report</title><style>
@page { size: ${size === "A4" ? "A4" : size === "MM58" ? "58mm auto" : "80mm auto"}; margin: 3mm; }
body { margin: 0; font: 11px/1.4 ui-monospace, Consolas, monospace; color: #000; }
.r { width: ${width}; margin: 0 auto; } h1 { font-size: 14px; text-align: center; margin: 0 0 2px; }
table { width: 100%; border-collapse: collapse; } hr { border: 0; border-top: 1px dashed #000; margin: 4px 0; }
</style></head><body><div class="r">
<h1>${esc(shopName)}</h1><div style="text-align:center">Z REPORT · ${esc(s.tillName)} · ${esc(s.branchName)}</div><hr>
<table>${row("Cashier", s.userName)}${row("Opened", dateTime(s.openedAt))}${row("Closed", s.closedAt ? dateTime(s.closedAt) : "still open")}</table><hr>
<table>${row("Sales", String(z.sales))}${row("Gross sales", money(z.grossSales, false))}${row("Discounts", money(z.discounts, false))}${row("Returns (" + z.returns + ")", money(z.returnsValue, false))}${row("Cancelled bills", String(z.cancelled))}${row("Tax", money(z.tax, false))}${row("Net sales", money(z.netSales, false), true)}</table><hr>
<table>${z.payments.map((p) => row(METHOD[p.method] ?? p.method, money(p.amount, false))).join("")}</table><hr>
<table>${row("Opening float", money(z.openingFloat, false))}${row("+ Cash sales", money(z.cashSales, false))}${row("+ Cash received", money(z.cashReceipts, false))}${row("+ Cash in", money(z.cashIn, false))}${row("− Cash refunds", money(z.cashRefunds, false))}${row("− Cash out", money(z.cashOut, false))}${row("− Expenses", money(z.cashExpenses, false))}${row("− Used phones bought", money(z.cashBuybacks, false))}${row("Expected cash", money(z.expectedCash, false), true)}${row("Counted cash", z.countedCash ? money(z.countedCash, false) : null, true)}${row("Difference", z.difference ? money(z.difference, false) : null, true)}</table><hr>
<div style="text-align:center">Signature ____________________</div>
</div></body></html>`;
}
