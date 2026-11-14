"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import * as React from "react";
import { PageHeader, Pager, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { BRAND } from "@/lib/brand";
import { consoleHref } from "@/lib/routing";
import { dateTime, label } from "@/lib/format";

/** PLT-27: everything platform users did, newest first. */
export default function PlatformAuditPage() {
  const [page, setPage] = React.useState(0);
  const audit = useQuery({
    queryKey: ["platform-audit", page],
    queryFn: () => api<Schemas["PageResponsePlatformAuditEntry"]>(`/platform/audit?page=${page}&size=50`),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader title="Audit log" description={`Every action by ${BRAND.company} staff`} />
      <Panel padded={false}>
        {audit.isPending ? (
          <TableSkeleton />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>When</Th>
                  <Th>Who</Th>
                  <Th>Action</Th>
                  <Th>Shop</Th>
                  <Th>Details</Th>
                </tr>
              </thead>
              <tbody>
                {audit.data?.items.map((a) => (
                  <tr key={a.id}>
                    <Td className="whitespace-nowrap text-muted">{dateTime(a.at)}</Td>
                    <Td>{a.actorName ?? "System"}</Td>
                    <Td>{label(a.action)}</Td>
                    <Td>{a.shopId ? <Link href={consoleHref(`/shops/${a.shopId}`)} className="font-mono text-aqua hover:underline">{a.shopCode}</Link> : "—"}</Td>
                    <Td className="max-w-sm truncate font-mono text-xs text-faint" title={a.details ?? ""}>
                      {a.details}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pager page={page} size={50} total={audit.data?.total ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
