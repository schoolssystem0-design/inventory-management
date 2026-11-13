"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import * as React from "react";
import { toast } from "sonner";
import { branchFilter, type Column, ListPage } from "@/components/list-page";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/fields";
import { api, errorMessage, type Schemas } from "@/lib/api/client";
import { dateTime } from "@/lib/format";
import { P, useBranch, useCan, useShop, useShopRouter } from "@/lib/shop-context";

type Row = Schemas["CountListItem"];

export default function CountsPage() {
  const can = useCan();
  const shop = useShop();
  const [starting, setStarting] = React.useState(false);
  const columns: Column<Row>[] = [
    { label: "Count", render: (c) => <span className="font-mono font-medium">{c.number}</span> },
    { label: "Started", render: (c) => <span className="text-muted">{dateTime(c.startedAt)}</span> },
    { label: "What", render: (c) => c.scope },
    { label: "Branch", render: (c) => <span className="text-muted">{c.branchName}</span>, hidden: shop.branches.length < 2 },
    { label: "Counted", render: (c) => `${c.counted} / ${c.lines}`, align: "right" },
    { label: "Status", render: (c) => <Status value={c.status} /> },
  ];
  return (
    <>
      <ListPage<Row>
        title="Stock counts"
        description="Count the shelves by scanning; posting sets stock to what you counted and records the difference."
        path="/stock-counts"
        queryKey="stock-counts"
        columns={columns}
        actions={
          can(P.STOCK_COUNT) && (
            <Button onClick={() => setStarting(true)}>
              <Plus /> Start a count
            </Button>
          )
        }
        filters={[
          ...branchFilter(shop.branches),
          {
            kind: "select",
            param: "status",
            label: "Status",
            options: [
              { value: "", label: "All" },
              { value: "OPEN", label: "Open" },
              { value: "POSTED", label: "Posted" },
              { value: "CANCELLED", label: "Cancelled" },
            ],
          },
        ]}
        rowHref={(c) => `/stock/counts/${c.id}`}
        empty={{ title: "No counts yet" }}
      />
      {starting && <StartDialog onClose={() => setStarting(false)} />}
    </>
  );
}

function StartDialog({ onClose }: { onClose: () => void }) {
  const shop = useShop();
  const router = useShopRouter();
  const [branch] = useBranch();
  const [branchId, setBranchId] = React.useState<number | null>(branch?.id ?? null);
  const [categoryId, setCategoryId] = React.useState("");
  const [note, setNote] = React.useState("");
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api<Schemas["CategoryView"][]>("/categories") });
  const start = useMutation({
    mutationFn: () =>
      api<Schemas["CountView"]>("/stock-counts", {
        method: "POST",
        body: {
          branchId,
          categoryId: categoryId ? Number(categoryId) : null,
          scope: categoryId ? categories.data?.find((c) => String(c.id) === categoryId)?.name : null,
          note: note || null,
        },
      }),
    onSuccess: (c) => router.push(`/stock/counts/${c.id}`),
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Start a stock count"
      description="The system quantity of every product in scope is noted now; sales made while you count are allowed for."
      footer={
        <Button onClick={() => start.mutate()} loading={start.isPending}>
          Start
        </Button>
      }
    >
      <div className="space-y-4">
        {shop.branches.length > 1 && (
          <Field label="Branch">
            <Select value={branchId ?? ""} onChange={(e) => setBranchId(Number(e.target.value))}>
              {shop.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="What to count">
          <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">All products</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                Category: {c.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Note">
          <Input value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
      </div>
    </Dialog>
  );
}
