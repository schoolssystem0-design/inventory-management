"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { Bell } from "lucide-react";
import * as React from "react";
import { Dialog } from "@/components/ui/dialog";
import { Empty } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { dateTime } from "@/lib/format";
import { useShopRouter } from "@/lib/shop-context";

type Page = Schemas["PageResponseInboxItem"];

/** The bell (NTF-04): stock alerts, subscription reminders and other in-app messages for this user. */
export function NotificationBell() {
  const [open, setOpen] = React.useState(false);
  const client = useQueryClient();
  const router = useShopRouter();
  const summary = useQuery({
    queryKey: ["inbox-summary"],
    queryFn: () => api<Schemas["InboxSummary"]>("/notifications/summary"),
    refetchInterval: 120_000,
  });
  const list = useQuery({
    queryKey: ["inbox"],
    queryFn: () => api<Page>("/notifications?size=30"),
    enabled: open,
  });
  const read = useMutation({
    mutationFn: (id: number) => api(`/notifications/${id}/read`, { method: "POST" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["inbox"] });
      client.invalidateQueries({ queryKey: ["inbox-summary"] });
    },
  });
  const readAll = useMutation({
    mutationFn: () => api("/notifications/read-all", { method: "POST" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["inbox"] });
      client.invalidateQueries({ queryKey: ["inbox-summary"] });
    },
  });
  const unread = summary.data?.unread ?? 0;
  return (
    <>
      <button
        className="relative rounded-lg p-2 text-faint hover:bg-raised hover:text-ink"
        aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"}
        onClick={() => setOpen(true)}
      >
        <Bell className="size-5 lg:size-4" />
        {unread > 0 && (
          <span className="absolute top-1 right-1 grid min-w-4 place-items-center rounded-full bg-aqua px-1 text-[10px] font-bold text-on-aqua">
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>
      <Dialog
        open={open}
        onOpenChange={setOpen}
        title="Notifications"
        footer={
          unread > 0 ? (
            <button className="text-sm font-semibold text-aqua" onClick={() => readAll.mutate()}>
              Mark all as read
            </button>
          ) : undefined
        }
      >
        {list.data && list.data.items.length === 0 && <Empty title="Nothing new" />}
        <ul className="-mx-2 space-y-1">
          {list.data?.items.map((n) => (
            <li key={n.id}>
              <button
                className={cn("w-full rounded-lg px-3 py-2.5 text-left hover:bg-raised", !n.read && "bg-aqua/5")}
                onClick={() => {
                  if (!n.read) read.mutate(n.id);
                  if (n.link) {
                    setOpen(false);
                    router.push(n.link);
                  }
                }}
              >
                <span className="flex items-center gap-2">
                  {!n.read && <span className="size-2 shrink-0 rounded-full bg-aqua" aria-label="Unread" />}
                  <span className="text-sm font-semibold">{n.title}</span>
                  <span className="ml-auto text-xs text-faint">{dateTime(n.at)}</span>
                </span>
                <span className="mt-0.5 block text-sm text-muted">{n.body}</span>
              </button>
            </li>
          ))}
        </ul>
      </Dialog>
    </>
  );
}
