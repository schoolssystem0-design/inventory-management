"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { CheckCircle2, CloudOff, Gift, Minimize2, PauseCircle, Pill, Printer, RefreshCw, Search, Send, Trash2, User, X, Zap } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { useApproval } from "@/components/approval";
import { ScanButton } from "@/components/barcode-scanner";
import { Button } from "@/components/ui/button";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { Input, Select } from "@/components/ui/fields";
import { Badge, ErrorState, Skeleton } from "@/components/ui/layout";
import { ApiError, api, errorMessage, type Schemas } from "@/lib/api/client";
import { money, qty as fmtQty } from "@/lib/format";
import { cmpMoney, subMoney, toPaisa, ZERO } from "@/lib/money";
import { P, useBranch, useCan, useFeature, useShop } from "@/lib/shop-context";
import { printHtml, receiptHtml } from "@/features/receipts/receipt";
import { addProduct, type Cart, type CartLine, EMPTY_CART, lineQty, type Payment, removeLine, replaceLine, saleBody, totals, trimQty } from "./cart";
import { type PosItem, toCartProduct } from "./catalog-cache";
import { CustomerDialog, HeldDialog, KeyHint, LineDialog, OpenTillPanel, PaymentDialog, SerialPrompt } from "./pos-dialogs";
import { CouponControl, offersText, PrescriptionDialog, prescriptionNeed, useLoyalty } from "./pos-extras";
import { type Applied, nowParts } from "./promotions";
import * as queue from "./queue";
import { usePosCatalog } from "./use-catalog";

type Session = Schemas["SessionView"];

function storageKey(shop: string, branch: number | null) {
  return `pos-cart:${shop}:${branch ?? 0}`;
}

function readCart(key: string): Cart {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...EMPTY_CART, ...(JSON.parse(raw) as Cart) } : EMPTY_CART;
  }
  catch {
    return EMPTY_CART;
  }
}

function usePref(key: string, initial: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = React.useState(initial);
  React.useEffect(() => {
    const t = window.setTimeout(() => {
      try {
        const v = localStorage.getItem(key);
        if (v !== null) setValue(v === "1");
      }
      catch {
        // default
      }
    }, 0);
    return () => window.clearTimeout(t);
  }, [key]);
  return [
    value,
    (v: boolean) => {
      setValue(v);
      try {
        localStorage.setItem(key, v ? "1" : "0");
      }
      catch {
        // not remembered
      }
    },
  ];
}

/** The till (POS-01…12). */
export function PosScreen() {
  const shop = useShop();
  const can = useCan();
  const client = useQueryClient();
  const [branch, chooseBranch] = useBranch();
  const branchId = branch?.id ?? null;

  const sessions = useQuery({ queryKey: ["till-current"], queryFn: () => api<Session[]>("/till-sessions/current") });
  const session = sessions.data?.find((s) => s.branchId === branchId) ?? null;
  const tills = useQuery({ queryKey: ["tills"], queryFn: () => api<Schemas["TillView"][]>("/tills"), enabled: sessions.isSuccess && !session });
  const openTill = useMutation({
    mutationFn: (v: { tillId: number; float: string }) => api<Session>("/till-sessions", { method: "POST", body: { tillId: v.tillId, openingFloat: v.float } }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["till-current"] }),
  });

  if (!branch) return <ErrorState message="You have no branch to sell from. Ask the owner to give you a branch." />;
  if (sessions.isPending) return <Skeleton className="h-[70vh]" />;
  if (sessions.isError) return <ErrorState message={errorMessage(sessions.error)} onRetry={() => sessions.refetch()} />;

  return (
    <>
      {shop.branches.length > 1 && (
        <div className="no-print mb-3 flex items-center gap-2 text-sm">
          <span className="text-muted">Branch</span>
          <Select value={branch.id} onChange={(e) => chooseBranch(Number(e.target.value))} className="h-8 w-auto py-0" aria-label="Branch">
            {shop.branches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </Select>
        </div>
      )}
      {session ? (
        <Till key={`${branch.id}-${session.id}`} session={session} branchId={branch.id} shopCode={shop.shop.code} />
      ) : (
        <OpenTillPanel
          tills={(tills.data ?? []).filter((t) => t.active && t.branchId === branch.id)}
          busy={openTill.isPending}
          error={openTill.error ? errorMessage(openTill.error) : null}
          onOpen={(tillId, float) => openTill.mutate({ tillId, float })}
        />
      )}
      {!can(P.POS_SELL) && <p className="mt-4 text-sm text-warn">You may not sell. Ask the owner for the cashier role.</p>}
    </>
  );
}

function Till({ session, branchId, shopCode }: { session: Session; branchId: number; shopCode: string }) {
  const shop = useShop();
  const can = useCan();
  const feature = useFeature();
  const client = useQueryClient();
  const settings = shop.settings;
  const catalog = usePosCatalog(shopCode, branchId);
  const approval = useApproval();
  const methods = useQuery({ queryKey: ["payment-methods"], queryFn: () => api<Schemas["PaymentMethodView"][]>("/payment-methods"), staleTime: 300_000 });

  const key = storageKey(shopCode, branchId);
  const [cart, setCartState] = React.useState<Cart>(EMPTY_CART);
  const saleKey = React.useRef<string>(queue.newKey());
  React.useEffect(() => {
    const t = window.setTimeout(() => setCartState(readCart(key)), 0);
    return () => window.clearTimeout(t);
  }, [key]);
  const setCart = React.useCallback(
    (update: (c: Cart) => Cart) => {
      setCartState((c) => {
        const next = update(c);
        try {
          localStorage.setItem(key, JSON.stringify(next));
        }
        catch {
          // not saved
        }
        return next;
      });
    },
    [key],
  );

  const [query, setQuery] = React.useState("");
  const [highlight, setHighlight] = React.useState(0);
  const [fast, setFast] = usePref("pos-fast", false);
  const [autoPrint, setAutoPrint] = usePref("pos-autoprint", true);
  const [editing, setEditing] = React.useState<string | null>(null);
  const [serialFor, setSerialFor] = React.useState<PosItem | null>(null);
  const [payOpen, setPayOpen] = React.useState(false);
  const [customerOpen, setCustomerOpen] = React.useState(false);
  const [heldOpen, setHeldOpen] = React.useState(false);
  const [clearOpen, setClearOpen] = React.useState(false);
  const [queueOpen, setQueueOpen] = React.useState(false);
  const [done, setDone] = React.useState<Schemas["SaleView"] | null>(null);
  const [rxOpen, setRxOpen] = React.useState(false);
  const [queued, setQueued] = React.useState<queue.QueuedSale[]>([]);
  const search = React.useRef<HTMLInputElement>(null);

  React.useEffect(() => queue.subscribe(setQueued), []);
  React.useEffect(() => {
    const retry = () =>
      void queue.flush().then((sent) => {
        if (sent.length) {
          toast.success(`${sent.length} saved sale${sent.length === 1 ? "" : "s"} sent`);
          void catalog.refresh();
        }
      });
    retry();
    const timer = window.setInterval(retry, 10_000);
    window.addEventListener("online", retry);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("online", retry);
    };
  }, [catalog.refresh]); // eslint-disable-line react-hooks/exhaustive-deps

  const promotionsOn = feature("promotions");
  const promo = promotionsOn && catalog.extras.offers.length ? { offers: catalog.extras.offers, ...nowParts() } : null;
  const t = totals(cart, settings.taxMode, settings.taxRate, promo);
  const rx = feature("prescriptions") ? prescriptionNeed(cart, catalog.extras.settings?.prescriptionRequired ?? true) : { needed: false, controlled: false, names: [] };
  const loyaltyOn = feature("loyalty") && !!catalog.extras.settings?.loyalty;
  const loyalty = useLoyalty(cart.customer?.id, loyaltyOn);
  /** Pay: a prescription first when the bill needs one. */
  const kitchenOn = feature("kitchen");
  const orderType = cart.orderType ?? "TAKEAWAY";
  const startPay = () => {
    if (!cart.lines.length || t.invalid) return;
    if (kitchenOn && orderType === "DINE_IN" && !cart.tableNo?.trim()) {
      toast.error("Enter the table number for a dine-in order");
      return;
    }
    if (rx.needed && !cart.prescription) setRxOpen(true);
    else setPayOpen(true);
  };
  const results = React.useMemo(() => (catalog.index && query.trim() ? catalog.index.search(query, fast ? 8 : 30) : []), [catalog.index, query, fast]);
  const focusSearch = () => window.setTimeout(() => search.current?.focus(), 0);

  function add(item: PosItem, unitIndex: number | null = null, serial?: string) {
    const product = toCartProduct(item);
    if (item.kind === "SERIAL" && !serial) {
      setSerialFor(item);
      return;
    }
    const unit = unitIndex === null ? null : product.units[unitIndex] ?? null;
    if (!settings.allowNegativeStock && item.stock && toPaisa(item.stock) <= BigInt(0) && item.kind !== "SERVICE") {
      toast.warning(`${item.name} shows no stock at this branch`);
    }
    setCart((c) => addProduct(c, product, unit, "1", serial));
    setQuery("");
    setHighlight(0);
    focusSearch();
  }

  // Scans made while the catalogue is still loading wait here and are added as soon as it is ready.
  const waiting = React.useRef<string[]>([]);
  async function enter(scanned?: string) {
    const text = (scanned ?? query).trim();
    if (!text) {
      if (cart.lines.length) startPay();
      return;
    }
    if (!catalog.index) {
      waiting.current.push(text);
      setQuery("");
      toast.info("Loading products — the scan will be added in a moment");
      return;
    }
    // Weighed barcodes and quantity prefix: "3*code" adds 3.
    const star = /^(\d+(?:\.\d{1,3})?)\*(.+)$/.exec(text);
    const code = star ? star[2] : text;
    const hit = catalog.index?.code(code);
    if (hit) {
      if (star && hit.item.kind !== "SERIAL") {
        const product = toCartProduct(hit.item);
        setCart((c) => addProduct(c, product, hit.unitIndex === null ? null : product.units[hit.unitIndex], star[1]));
        setQuery("");
        return;
      }
      add(hit.item, hit.unitIndex);
      return;
    }
    // Weighing-scale label: item code and weight (or price) in the barcode.
    const weighed = feature("scale_barcodes") ? catalog.index?.scale(code, catalog.extras.settings?.scaleValueKind ?? "WEIGHT") : null;
    if (weighed) {
      setCart((c) => addProduct(c, toCartProduct(weighed.item), null, trimQty(weighed.qty)));
      setQuery("");
      return;
    }
    if (!scanned && results[highlight]) {
      add(results[highlight]);
      return;
    }
    if (feature("serials") && /^[A-Za-z0-9-]{6,}$/.test(code)) {
      try {
        const s = await api<Schemas["SerialView"]>(`/serials/${encodeURIComponent(code)}`);
        const item = catalog.index?.items.find((i) => i.id === s.productId);
        if (item && s.status === "IN_STOCK") {
          add(item, null, s.serial);
          return;
        }
        toast.error(`IMEI ${code} is ${s.status.toLowerCase().replace(/_/g, " ")}${s.branchName ? ` at ${s.branchName}` : ""}`);
        return;
      }
      catch {
        // not a serial
      }
    }
    toast.error(`Nothing found for “${text}”`);
  }

  React.useEffect(() => {
    if (!catalog.index || waiting.current.length === 0) return;
    const codes = waiting.current;
    waiting.current = [];
    const t = window.setTimeout(() => codes.forEach((c) => void enter(c)), 0);
    return () => window.clearTimeout(t);
  }, [catalog.index]); // eslint-disable-line react-hooks/exhaustive-deps

  const sell = useMutation({
    mutationFn: async (payments: Payment[]) => {
      const body = saleBody(cart, payments, session.id);
      const k = saleKey.current;
      try {
        return { sale: await approval.run((headers) => queue.send(k, body, headers)), queued: false };
      }
      catch (e) {
        if (queue.isRetryable(e)) {
          queue.enqueue({ key: k, body, total: t.total });
          return { sale: null, queued: true };
        }
        throw e;
      }
    },
    onSuccess: async ({ sale, queued: wasQueued }) => {
      setPayOpen(false);
      setCart(() => EMPTY_CART);
      saleKey.current = queue.newKey();
      if (wasQueued) {
        toast.warning("No connection — the sale is saved on this device and will be sent automatically.");
        focusSearch();
        return;
      }
      if (!sale) return;
      setDone(sale);
      void catalog.refresh();
      client.invalidateQueries({ queryKey: ["sales"] });
      if (autoPrint) void printReceipt(sale.id);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === "approval_required") return;
      toast.error(errorMessage(e));
    },
  });

  async function printReceipt(id: number) {
    try {
      const r = await api<Schemas["ReceiptView"]>(`/sales/${id}/receipt`);
      await printHtml(receiptHtml(r));
    }
    catch (e) {
      toast.error(`Receipt: ${errorMessage(e)}`);
    }
  }

  const hold = useMutation({
    mutationFn: () => api("/held-sales", { method: "POST", body: { branchId, cart, label: cart.customer?.name ?? cart.quick?.name ?? null } }),
    onSuccess: () => {
      setCart(() => EMPTY_CART);
      toast.success("Bill held. F7 brings it back.");
      client.invalidateQueries({ queryKey: ["held-sales"] });
      focusSearch();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  async function resume(h: Schemas["HeldSaleView"]) {
    if (cart.lines.length) {
      toast.error("Finish or hold the current bill first");
      return;
    }
    try {
      await api(`/held-sales/${h.id}`, { method: "DELETE" });
      setCart(() => ({ ...EMPTY_CART, ...(h.cart as unknown as Cart) }));
      setHeldOpen(false);
      client.invalidateQueries({ queryKey: ["held-sales"] });
      focusSearch();
    }
    catch (e) {
      toast.error(errorMessage(e));
    }
  }

  // Keyboard: F2 search, F4 customer, F7 held, F8 hold, F9 pay, Esc clear.
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const dialogOpen = payOpen || customerOpen || heldOpen || editing || serialFor || clearOpen || done || rxOpen;
      if (e.key === "F2") {
        e.preventDefault();
        search.current?.focus();
      }
      else if (e.key === "F4" && !dialogOpen) {
        e.preventDefault();
        setCustomerOpen(true);
      }
      else if (e.key === "F7" && !dialogOpen) {
        e.preventDefault();
        setHeldOpen(true);
      }
      else if (e.key === "F8" && !dialogOpen) {
        e.preventDefault();
        if (cart.lines.length) hold.mutate();
      }
      else if (e.key === "F9" && !dialogOpen) {
        e.preventDefault();
        startPay();
      }
      else if (e.key === "Escape" && !dialogOpen && document.activeElement === search.current) {
        if (query) setQuery("");
        else if (cart.lines.length) setClearOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const editingLine = cart.lines.find((l) => l.key === editing) ?? null;
  const customerLabel = cart.customer?.name ?? (cart.quick ? `${cart.quick.name || "New customer"} · ${cart.quick.phone}` : null);

  return (
    <div className={cn("grid gap-4", fast ? "lg:grid-cols-[1fr_minmax(380px,32rem)]" : "lg:grid-cols-[minmax(0,1fr)_minmax(360px,28rem)]")}>
      <h1 className="sr-only">Point of sale</h1>
      {/* ---------------- left: search and products */}
      <section className="min-w-0 space-y-3" aria-label="Products">
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-faint" aria-hidden />
            <Input
              ref={search}
              value={query}
              autoFocus
              onChange={(e) => {
                setQuery(e.target.value);
                setHighlight(0);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void enter();
                }
                else if (e.key === "ArrowDown") {
                  e.preventDefault();
                  setHighlight((h) => Math.min(h + 1, results.length - 1));
                }
                else if (e.key === "ArrowUp") {
                  e.preventDefault();
                  setHighlight((h) => Math.max(h - 1, 0));
                }
              }}
              placeholder={fast ? "Scan barcode (3*code for 3)" : "Scan, or type a name, SKU or formula  (F2)"}
              aria-label="Scan or search products"
              className={cn("pl-10", fast ? "h-14 text-xl" : "h-12 text-base")}
              autoComplete="off"
            />
          </div>
          <ScanButton onCode={(code) => enterCode(code)} />
          <Button variant="secondary" size="icon" onClick={() => setFast(!fast)} aria-label={fast ? "Normal till" : "Fast till"} title={fast ? "Normal till" : "Fast till (super store)"}>
            {fast ? <Minimize2 /> : <Zap />}
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs text-faint">
          <span>{session.tillName} · {session.userName}</span>
          <span>·</span>
          {catalog.index ? <span>{catalog.index.items.length.toLocaleString()} products ready</span> : <span>Loading products…</span>}
          {catalog.error && <Badge tone="warn">Offline copy</Badge>}
          <button className="inline-flex items-center gap-1 hover:text-ink" onClick={() => void catalog.refresh()}>
            <RefreshCw className="size-3" /> Refresh
          </button>
          {queued.length > 0 && (
            <button onClick={() => setQueueOpen(true)} className="ml-auto">
              <Badge tone="warn">
                <CloudOff className="size-3" /> {queued.length} sale{queued.length === 1 ? "" : "s"} waiting to send
              </Badge>
            </button>
          )}
        </div>

        {query.trim() && (
          <ul role="listbox" aria-label="Search results" className={cn("grid gap-2", fast ? "grid-cols-1" : "grid-cols-2 sm:grid-cols-3 xl:grid-cols-4")}>
            {results.length === 0 && <li className="col-span-full py-6 text-center text-sm text-faint">No product matches — Enter looks up the code</li>}
            {results.map((item, i) => (
              <li key={item.id} role="option" aria-selected={i === highlight}>
                <button
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => add(item)}
                  className={cn("tile flex h-full w-full flex-col items-start p-3 text-left", i === highlight && "ring-2 ring-aqua")}
                >
                  <span className="line-clamp-2 text-sm font-medium">{item.name}</span>
                  {item.urduName && <span className="urdu text-xs text-muted">{item.urduName}</span>}
                  <span className="mt-auto flex w-full items-end justify-between gap-2 pt-2">
                    <span className="font-mono text-sm text-aqua">{money(item.price, false)}</span>
                    {item.stock !== null && item.stock !== undefined && (
                      <span className={cn("text-xs", toPaisa(item.stock) <= BigInt(0) ? "text-bad" : "text-faint")}>{fmtQty(item.stock)} {item.unitName}</span>
                    )}
                  </span>
                  {item.formula && <span className="mt-1 text-[11px] text-faint">{item.formula}</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
        {!query.trim() && !fast && (
          <div className="tile hidden p-6 text-sm text-muted lg:block">
            <p className="font-semibold text-ink">Shortcuts</p>
            <ul className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1">
              <li><kbd className="font-mono text-aqua">F2</kbd> search / scan</li>
              <li><kbd className="font-mono text-aqua">F4</kbd> customer</li>
              <li><kbd className="font-mono text-aqua">F7</kbd> held bills</li>
              <li><kbd className="font-mono text-aqua">F8</kbd> hold this bill</li>
              <li><kbd className="font-mono text-aqua">F9</kbd> or Enter on empty search: pay</li>
              <li><kbd className="font-mono text-aqua">Esc</kbd> clear search, then the bill</li>
              <li><kbd className="font-mono text-aqua">3*code</kbd> adds 3 of an item</li>
            </ul>
          </div>
        )}
      </section>

      {/* ---------------- right: bill */}
      <section className="glass flex min-h-[40vh] flex-col lg:sticky lg:min-h-[60vh] lg:top-4 lg:max-h-[calc(100vh-6rem)]" aria-label="Bill">
        <div className="flex items-center gap-2 border-b border-line px-4 py-3">
          <button onClick={() => setCustomerOpen(true)} className="flex min-w-0 flex-1 items-center gap-2 rounded-lg px-1 py-1 text-left hover:bg-raised">
            <User className="size-4 shrink-0 text-faint" />
            <span className="min-w-0 truncate text-sm">{customerLabel ?? <span className="text-faint">Walk-in customer</span>}</span>
            {cart.customer?.balance && cart.customer.balance !== "0.00" && <Badge tone="warn">Owes {money(cart.customer.balance)}</Badge>}
            <KeyHint k="F4" className="ml-auto">{""}</KeyHint>
          </button>
          {customerLabel && (
            <button onClick={() => setCart((c) => ({ ...c, customer: null, quick: null }))} className="rounded-lg p-1 text-faint hover:text-ink" aria-label="Remove customer">
              <X className="size-4" />
            </button>
          )}
        </div>

        {kitchenOn && (
          <div className="flex flex-wrap items-center gap-2 border-b border-line px-4 py-2" role="group" aria-label="Order type">
            {[
              ["DINE_IN", "Dine-in"],
              ["TAKEAWAY", "Takeaway"],
              ...(feature("delivery") ? [["DELIVERY", "Delivery"]] : []),
            ].map(([value, text]) => (
              <button
                key={value}
                type="button"
                aria-pressed={orderType === value}
                onClick={() => setCart((c) => ({ ...c, orderType: value }))}
                className={cn("rounded-lg px-3 py-1.5 text-sm", orderType === value ? "bg-aqua text-on-aqua" : "bg-raised text-muted hover:text-ink")}
              >
                {text}
              </button>
            ))}
            {orderType === "DINE_IN" && (
              <Input
                value={cart.tableNo ?? ""}
                onChange={(e) => setCart((c) => ({ ...c, tableNo: e.target.value }))}
                placeholder="Table"
                aria-label="Table number"
                className="h-8 w-24"
              />
            )}
          </div>
        )}

        <ol className="flex-1 divide-y divide-line overflow-y-auto" aria-label="Items on the bill">
          {cart.lines.length === 0 && <li className="p-8 text-center text-sm text-faint">Scan or search to add items</li>}
          {cart.lines.map((line, i) => (
            <CartRow
              key={line.key}
              line={line}
              total={t.lines[i]?.total ?? ZERO}
              offer={t.lines[i]?.offer}
              onEdit={() => setEditing(line.key)}
              onQty={(q) => setCart((c) => replaceLine(c, line.key, { qty: q }))}
              onRemove={() => setCart((c) => removeLine(c, line.key))}
            />
          ))}
        </ol>

        <div className="space-y-2 border-t border-line px-4 py-3 text-sm">
          <div className="flex justify-between text-muted">
            <span>Subtotal · {t.items} item{t.items === 1 ? "" : "s"}</span>
            <span className="font-mono">{money(t.subtotal, false)}</span>
          </div>
          <label className="flex items-center justify-between gap-3 text-muted">
            <span>Bill discount</span>
            <Input
              value={cart.billDiscount}
              onChange={(e) => setCart((c) => ({ ...c, billDiscount: e.target.value }))}
              placeholder="0 or 5%"
              className="h-8 w-28 text-right font-mono"
              aria-label="Bill discount"
            />
          </label>
          {cmpMoney(t.offers, ZERO) > 0 && (
            <div className="flex justify-between text-good">
              <span className="inline-flex items-center gap-1.5">
                <Gift className="size-4" /> Offers{t.billOffer.name ? ` · ${t.billOffer.name}` : ""}
              </span>
              <span className="font-mono" title={offersText(t) ?? undefined}>−{money(t.offers, false)}</span>
            </div>
          )}
          {cmpMoney(t.discount, t.offers) > 0 && (
            <div className="flex justify-between text-good">
              <span>Discount</span>
              <span className="font-mono">−{money(subMoney(t.discount, t.offers), false)}</span>
            </div>
          )}
          {promotionsOn && (
            <CouponControl
              cart={cart}
              totals={t}
              onApply={(coupon) => setCart((c) => ({ ...c, coupon }))}
              onRemove={() => setCart((c) => ({ ...c, coupon: null }))}
            />
          )}
          {cart.prescription && (
            <div className="flex items-center justify-between gap-2 text-sm">
              <button className="inline-flex min-w-0 items-center gap-1.5 text-left text-muted hover:text-ink" onClick={() => setRxOpen(true)}>
                <Pill className="size-4 shrink-0" />
                <span className="truncate">
                  Rx: {cart.prescription.patientName} · {cart.prescription.doctorName}
                </span>
              </button>
              <button className="rounded p-0.5 text-faint hover:text-bad" onClick={() => setCart((c) => ({ ...c, prescription: null }))} aria-label="Remove prescription">
                <X className="size-4" />
              </button>
            </div>
          )}
          {cmpMoney(t.tax, ZERO) > 0 && (
            <div className="flex justify-between text-muted">
              <span>Tax {settings.taxMode === "INCLUSIVE" ? "(included)" : ""}</span>
              <span className="font-mono">{money(t.tax, false)}</span>
            </div>
          )}
          {t.invalid && <p className="text-bad">{t.invalid}</p>}
          <div className="flex items-end justify-between pt-1">
            <span className="text-base font-semibold">Total</span>
            <span className="font-mono text-3xl font-bold text-aqua" aria-live="polite">{money(t.total)}</span>
          </div>
        </div>

        <div className="grid grid-cols-[auto_auto_1fr] gap-2 border-t border-line p-3">
          <Button variant="secondary" onClick={() => setHeldOpen(true)} title="Held bills (F7)">
            <KeyHint k="F7">Held</KeyHint>
          </Button>
          <Button variant="secondary" onClick={() => hold.mutate()} disabled={!cart.lines.length} loading={hold.isPending} title="Hold this bill (F8)">
            <PauseCircle /> <KeyHint k="F8">Hold</KeyHint>
          </Button>
          <Button size="lg" onClick={startPay} disabled={!cart.lines.length || Boolean(t.invalid) || !methods.data} className="text-base">
            <KeyHint k="F9">Pay {money(t.total)}</KeyHint>
          </Button>
        </div>
        <label className="flex items-center gap-2 px-4 pb-3 text-xs text-faint">
          <input type="checkbox" className="accent-[#4fbfae]" checked={autoPrint} onChange={(e) => setAutoPrint(e.target.checked)} /> Print receipt after each sale
        </label>
      </section>

      {/* ---------------- dialogs */}
      <PaymentDialog
        open={payOpen}
        onClose={() => {
          setPayOpen(false);
          focusSearch();
        }}
        total={t.total}
        methods={methods.data ?? []}
        customer={cart.customer}
        loyalty={loyaltyOn && loyalty.data ? { points: loyalty.data.points, pointValue: loyalty.data.settings.pointValue, minRedeem: loyalty.data.settings.minRedeem } : null}
        busy={sell.isPending}
        onPay={(payments) => sell.mutate(payments)}
      />
      <PrescriptionDialog
        open={rxOpen}
        names={rx.names}
        controlled={rx.controlled}
        initial={cart.prescription ?? null}
        onClose={() => {
          setRxOpen(false);
          focusSearch();
        }}
        onSave={(prescription) => {
          setCart((c) => ({ ...c, prescription }));
          setRxOpen(false);
          setPayOpen(true);
        }}
      />
      <CustomerDialog
        open={customerOpen}
        canCreate
        onClose={() => {
          setCustomerOpen(false);
          focusSearch();
        }}
        onPick={(customer, quick) => {
          setCart((c) => ({ ...c, customer, quick }));
          setCustomerOpen(false);
          focusSearch();
        }}
      />
      <HeldDialog
        open={heldOpen}
        onClose={() => setHeldOpen(false)}
        branchId={branchId}
        onResume={resume}
        onDelete={async (id) => {
          try {
            await api(`/held-sales/${id}`, { method: "DELETE" });
            client.invalidateQueries({ queryKey: ["held-sales"] });
          }
          catch (e) {
            toast.error(errorMessage(e));
          }
        }}
      />
      <LineDialog
        line={editingLine}
        branchId={branchId}
        canPrice={can(P.POS_PRICE_OVERRIDE)}
        onClose={() => {
          setEditing(null);
          focusSearch();
        }}
        onSave={(patch) => {
          if (editingLine) setCart((c) => replaceLine(c, editingLine.key, patch));
          setEditing(null);
          focusSearch();
        }}
        onRemove={() => {
          if (editingLine) setCart((c) => removeLine(c, editingLine.key));
          setEditing(null);
          focusSearch();
        }}
      />
      <SerialPrompt
        product={serialFor}
        onClose={() => {
          setSerialFor(null);
          focusSearch();
        }}
        onAdd={(serial) => {
          if (serialFor) setCart((c) => addProduct(c, toCartProduct(serialFor), null, "1", serial));
          setSerialFor(null);
          setQuery("");
          focusSearch();
        }}
      />
      <ConfirmDialog
        open={clearOpen}
        onOpenChange={setClearOpen}
        title="Clear this bill?"
        description="All items on the bill are removed. Nothing is saved."
        confirmLabel="Clear bill"
        danger
        onConfirm={() => {
          setCart(() => EMPTY_CART);
          setClearOpen(false);
          focusSearch();
        }}
      />
      <Dialog open={queueOpen} onOpenChange={setQueueOpen} title="Sales waiting to send" description="They were saved on this device while the server could not be reached. Each is sent once, even if retried.">
        <ul className="space-y-2">
          {queued.map((q) => (
            <li key={q.key} className="flex items-center gap-3 rounded-lg bg-raised/60 px-3 py-2 text-sm">
              <span className="flex-1">
                <span className="font-mono">{money(q.total)}</span>
                <span className="block text-xs text-faint">
                  {new Date(q.createdAt).toLocaleTimeString()} · {q.attempts} tries{q.lastError ? ` · ${q.lastError}` : ""}
                </span>
              </span>
              <Button size="sm" variant="ghost" onClick={() => queue.drop(q.key)}>
                <Trash2 /> Discard
              </Button>
            </li>
          ))}
        </ul>
        <Button className="mt-4" onClick={() => void queue.flush()}>
          <Send /> Send now
        </Button>
      </Dialog>
      {done && (
        <Dialog
          open
          onOpenChange={(o) => {
            if (!o) {
              setDone(null);
              focusSearch();
            }
          }}
          title={`Sale ${done.number}`}
          footer={
            <>
              <Button variant="secondary" onClick={() => void printReceipt(done.id)}>
                <Printer /> Print receipt
              </Button>
              <Button
                autoFocus
                onClick={() => {
                  setDone(null);
                  focusSearch();
                }}
              >
                New sale (Enter)
              </Button>
            </>
          }
        >
          <div className="space-y-3 text-center">
            <CheckCircle2 className="mx-auto size-12 text-good" aria-hidden />
            {done.tokenNo && (
              <p>
                <span className="block text-sm text-muted">Token</span>
                <span className="font-mono text-5xl font-bold text-aqua" aria-label={`Token ${done.tokenNo}`}>#{done.tokenNo}</span>
                <span className="block text-sm text-muted">
                  {done.orderType === "DINE_IN" ? `Dine-in · Table ${done.tableNo ?? ""}` : done.orderType === "DELIVERY" ? "Delivery" : "Takeaway"}
                </span>
              </p>
            )}
            <p className="text-sm text-muted">Total {money(done.total)}</p>
            {done.change !== "0.00" && (
              <p>
                <span className="block text-sm text-muted">Give change</span>
                <span className="font-mono text-4xl font-bold text-good">{money(done.change)}</span>
              </p>
            )}
            {done.due !== "0.00" && <p className="text-warn">{money(done.due)} put on {done.customerName}&apos;s account</p>}
            {done.promoDiscount && done.promoDiscount !== "0.00" && <p className="text-good">Offers saved {money(done.promoDiscount)}</p>}
            {(done.pointsEarned > 0 || done.pointsUsed > 0) && (
              <p className="text-sm text-muted">
                {done.pointsUsed > 0 && `${done.pointsUsed} points used. `}
                {done.pointsEarned > 0 && `${done.pointsEarned} points earned.`}
              </p>
            )}
            {done.approvalNote && <p className="text-xs text-faint">Approved: {done.approvalNote}</p>}
          </div>
        </Dialog>
      )}
      {approval.dialog}
    </div>
  );

  function enterCode(code: string) {
    void enter(code);
  }
}

function CartRow({ line, total, offer, onEdit, onQty, onRemove }: { line: CartLine; total: string; offer?: Applied; onEdit: () => void; onQty: (q: string) => void; onRemove: () => void }) {
  const p = line.product;
  const serial = p.kind === "SERIAL";
  const step = (d: number) => {
    const n = Math.max(0, Number(line.qty || "0") + d);
    onQty(trimQty(n.toFixed(p.decimals ? 3 : 0)));
  };
  return (
    <li className="group flex items-start gap-3 px-4 py-2.5">
      <button onClick={onEdit} className="min-w-0 flex-1 text-left">
        <span className="block truncate text-sm font-medium">{p.name}</span>
        <span className="block text-xs text-faint">
          {fmtQty(lineQty(line))} {line.unitName} × {money(line.price, false)}
          {line.price !== line.listPrice && <span className="text-warn"> (was {money(line.listPrice, false)})</span>}
          {line.discount && <span className="text-good"> · −{line.discount}</span>}
          {line.batchLabel && <span> · {line.batchLabel}</span>}
        </span>
        {offer?.offerId != null && (
          <span className="block text-xs text-good">
            {offer.name} · −{money(offer.amount, false)}
          </span>
        )}
        {serial && line.serials.length > 0 && <span className="block truncate font-mono text-[11px] text-faint">{line.serials.join(", ")}</span>}
      </button>
      {!serial && (
        <div className="flex items-center rounded-lg bg-raised">
          <button className="px-2 py-1 text-muted hover:text-ink" onClick={() => step(-1)} aria-label={`One less ${p.name}`}>
            −
          </button>
          <span className="min-w-8 text-center font-mono text-sm">{fmtQty(line.qty)}</span>
          <button className="px-2 py-1 text-muted hover:text-ink" onClick={() => step(1)} aria-label={`One more ${p.name}`}>
            +
          </button>
        </div>
      )}
      <span className="w-20 text-right font-mono text-sm">{money(total, false)}</span>
      <button onClick={onRemove} className="rounded p-0.5 text-faint opacity-60 hover:text-bad group-hover:opacity-100" aria-label={`Remove ${p.name}`}>
        <X className="size-4" />
      </button>
    </li>
  );
}

