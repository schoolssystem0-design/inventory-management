/**
 * Plain printable documents for the shop-type modules (repair job card, delivery challan, installment schedule,
 * controlled-medicine register). A4 by default, or 80 mm for the receipt printer. Printed with printHtml.
 */
const esc = (s: string | number | null | undefined) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] as string);

export type DocSection =
  | { kind: "fields"; title?: string; rows: [string, string | number | null | undefined][] }
  | { kind: "table"; title?: string; head: string[]; rows: (string | number | null | undefined)[][]; right?: number[]; foot?: (string | number | null | undefined)[] }
  | { kind: "text"; title?: string; text: string }
  | { kind: "signatures"; labels: string[] };

export function docHtml(opts: { shopName: string; shopLines?: (string | null | undefined)[]; title: string; number?: string | null; date?: string | null; sections: DocSection[]; narrow?: boolean; copy?: string }): string {
  const width = opts.narrow ? "72mm" : "190mm";
  const body = opts.sections
    .map((s) => {
      const title = "title" in s && s.title ? `<h3>${esc(s.title)}</h3>` : "";
      if (s.kind === "fields") {
        return `${title}<table class="fields">${s.rows
          .filter(([, v]) => v !== null && v !== undefined && v !== "")
          .map(([k, v]) => `<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`)
          .join("")}</table>`;
      }
      if (s.kind === "table") {
        const right = new Set(s.right ?? []);
        const cell = (v: string | number | null | undefined, i: number, tag: string) => `<${tag}${right.has(i) ? ' class="r"' : ""}>${esc(v)}</${tag}>`;
        return `${title}<table class="grid"><thead><tr>${s.head.map((h, i) => cell(h, i, "th")).join("")}</tr></thead><tbody>${s.rows
          .map((r) => `<tr>${r.map((v, i) => cell(v, i, "td")).join("")}</tr>`)
          .join("")}</tbody>${s.foot ? `<tfoot><tr>${s.foot.map((v, i) => cell(v, i, "td")).join("")}</tr></tfoot>` : ""}</table>`;
      }
      if (s.kind === "text") return `${title}<p>${esc(s.text)}</p>`;
      return `<div class="sign">${s.labels.map((l) => `<div><span></span>${esc(l)}</div>`).join("")}</div>`;
    })
    .join("");
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(opts.title)}</title><style>
@page { margin: ${opts.narrow ? "2mm" : "10mm"}; }
* { box-sizing: border-box; }
body { font: ${opts.narrow ? "11px" : "12px"}/1.4 system-ui, sans-serif; color: #000; width: ${width}; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #000; padding-bottom: 6px; margin-bottom: 8px; ${opts.narrow ? "flex-direction: column;" : ""} }
h1 { font-size: ${opts.narrow ? "14px" : "18px"}; margin: 0; } h2 { font-size: ${opts.narrow ? "13px" : "16px"}; margin: 0; text-transform: uppercase; }
h3 { font-size: 12px; margin: 12px 0 4px; text-transform: uppercase; letter-spacing: .03em; }
.muted { color: #444; } .copy { border: 1px solid #000; padding: 1px 6px; font-weight: 600; }
table { width: 100%; border-collapse: collapse; }
.fields th { text-align: left; font-weight: 600; width: 34%; padding: 2px 6px 2px 0; vertical-align: top; } .fields td { padding: 2px 0; }
.grid th, .grid td { border: 1px solid #999; padding: 3px 5px; text-align: left; } .grid th { background: #eee; }
.grid tfoot td { font-weight: 700; } .r { text-align: right !important; }
.sign { display: flex; gap: 24px; margin-top: 40px; } .sign div { flex: 1; text-align: center; } .sign span { display: block; border-top: 1px solid #000; margin-bottom: 4px; }
p { margin: 4px 0; white-space: pre-wrap; }
</style></head><body><header><div><h1>${esc(opts.shopName)}</h1>${(opts.shopLines ?? [])
    .filter(Boolean)
    .map((l) => `<div class="muted">${esc(l)}</div>`)
    .join("")}</div><div style="text-align:${opts.narrow ? "left" : "right"}"><h2>${esc(opts.title)}</h2>${opts.number ? `<div><b>${esc(opts.number)}</b></div>` : ""}${
    opts.date ? `<div class="muted">${esc(opts.date)}</div>` : ""
  }${opts.copy ? `<div class="copy">${esc(opts.copy)}</div>` : ""}</div></header>${body}</body></html>`;
}
