"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import * as React from "react";
import { Input, Select } from "@/components/ui/fields";
import { Badge, PageHeader, Pager, Panel, Table, TableSkeleton, Tabs, Td, Th } from "@/components/ui/layout";
import { api, type Schemas } from "@/lib/api/client";
import { dateTime, label } from "@/lib/format";
import { P, useCan } from "@/lib/shop-context";

/** Activity history (ACC-07) and login history (ACC-06). */
export default function SecurityPage() {
  const can = useCan();
  const [tab, setTab] = React.useState<"activity" | "logins">(can(P.AUDIT_VIEW) ? "activity" : "logins");
  return (
    <>
      <PageHeader title="Activity and logins" description="Who changed what, and every sign-in attempt" />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "activity", label: "Activity", hidden: !can(P.AUDIT_VIEW) },
          { value: "logins", label: "Logins", hidden: !can(P.LOGIN_HISTORY_VIEW) },
        ]}
      />
      {tab === "activity" ? <Activity /> : <Logins />}
    </>
  );
}

function Activity() {
  const [q, setQ] = React.useState("");
  const [page, setPage] = React.useState(0);
  const audit = useQuery({
    queryKey: ["audit", q, page],
    queryFn: () => api<Schemas["PageResponseAuditEntry"]>(`/audit?page=${page}&size=50&q=${encodeURIComponent(q)}`),
    placeholderData: keepPreviousData,
  });
  return (
    <Panel padded={false}>
      <div className="border-b border-line p-3">
        <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Search actions or people" aria-label="Search activity" className="max-w-sm" />
      </div>
      {audit.isPending ? (
        <TableSkeleton />
      ) : (
        <>
          <Table>
            <thead>
              <tr>
                <Th>When</Th>
                <Th>Who</Th>
                <Th>What</Th>
                <Th>Details</Th>
              </tr>
            </thead>
            <tbody>
              {audit.data?.items.map((a) => (
                <tr key={a.id}>
                  <Td className="whitespace-nowrap text-muted">{dateTime(a.at)}</Td>
                  <Td>
                    {a.actorName ?? "System"}
                    {a.support && <Badge tone="info" className="ml-2">Support</Badge>}
                  </Td>
                  <Td>{label(a.action)}</Td>
                  <Td className="max-w-md truncate font-mono text-xs text-faint" title={a.after ?? ""}>
                    {a.entityType ? `${a.entityType}${a.entityId ? " #" + a.entityId : ""} ` : ""}
                    {a.after ?? ""}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <Pager page={page} size={50} total={audit.data?.total ?? 0} onPage={setPage} />
        </>
      )}
    </Panel>
  );
}

function Logins() {
  const [result, setResult] = React.useState("");
  const [page, setPage] = React.useState(0);
  const logins = useQuery({
    queryKey: ["logins", result, page],
    queryFn: () => api<Schemas["PageResponseLoginEventView"]>(`/login-events?page=${page}&size=50${result ? "&result=" + result : ""}`),
    placeholderData: keepPreviousData,
  });
  const tone = (r: string) => (r === "SUCCESS" ? "good" : r === "BLOCKED" ? "bad" : "warn");
  return (
    <Panel padded={false}>
      <div className="border-b border-line p-3">
        <Select value={result} onChange={(e) => { setResult(e.target.value); setPage(0); }} className="w-auto" aria-label="Result">
          <option value="">All attempts</option>
          <option value="SUCCESS">Successful</option>
          <option value="INVALID">Wrong password</option>
          <option value="BLOCKED">Blocked</option>
          <option value="TOTP_INVALID">Wrong authenticator code</option>
        </Select>
      </div>
      {logins.isPending ? (
        <TableSkeleton />
      ) : (
        <>
          <Table>
            <thead>
              <tr>
                <Th>When</Th>
                <Th>Username</Th>
                <Th>Result</Th>
                <Th>From</Th>
              </tr>
            </thead>
            <tbody>
              {logins.data?.items.map((l) => (
                <tr key={l.id}>
                  <Td className="whitespace-nowrap text-muted">{dateTime(l.at)}</Td>
                  <Td className="font-mono">{l.username}</Td>
                  <Td>
                    <Badge tone={tone(l.result)}>{label(l.result)}</Badge>
                  </Td>
                  <Td className="max-w-xs truncate text-xs text-faint" title={l.userAgent ?? ""}>
                    {l.ip}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <Pager page={page} size={50} total={logins.data?.total ?? 0} onPage={setPage} />
        </>
      )}
    </Panel>
  );
}
