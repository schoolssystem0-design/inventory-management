"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { DatabaseBackup, Download } from "lucide-react";
import { toast } from "sonner";
import { Status } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Empty, ErrorState, PageHeader, Panel, Table, TableSkeleton, Td, Th } from "@/components/ui/layout";
import { api, apiBlob, errorMessage, saveBlob, type Schemas } from "@/lib/api/client";
import { dateTime, label } from "@/lib/format";
import { P, useCan } from "@/lib/shop-context";

/** Exports and backup (NFR-13): big reports and the full Excel backup are made in the background. */
export default function ExportsPage() {
  const can = useCan();
  const client = useQueryClient();
  const jobs = useQuery({
    queryKey: ["exports"],
    queryFn: () => api<Schemas["ExportJob"][]>("/exports"),
    refetchInterval: (q) => (q.state.data?.some((j) => j.status === "QUEUED" || j.status === "RUNNING") ? 3000 : false),
  });
  const backup = useMutation({
    mutationFn: () => api("/backup", { method: "POST" }),
    onSuccess: () => {
      toast.success("Backup started");
      client.invalidateQueries({ queryKey: ["exports"] });
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <>
      <PageHeader
        title="Exports and backup"
        description="Files are kept for 7 days. The backup is every product, customer, supplier, sale and purchase in one Excel workbook."
        actions={
          can(P.DATA_EXPORT) && (
            <Button onClick={() => backup.mutate()} loading={backup.isPending}>
              <DatabaseBackup /> Make a full backup
            </Button>
          )
        }
      />
      <Panel padded={false}>
        {jobs.isPending ? (
          <TableSkeleton />
        ) : jobs.isError ? (
          <ErrorState message={errorMessage(jobs.error)} onRetry={() => jobs.refetch()} />
        ) : jobs.data.length === 0 ? (
          <Empty title="No exports yet" description="Start one from a report or make a backup." />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>What</Th>
                <Th>Asked</Th>
                <Th>Format</Th>
                <Th align="right">Rows</Th>
                <Th>Status</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {jobs.data.map((j) => (
                <tr key={j.id}>
                  <Td>{label(j.kind)}</Td>
                  <Td className="text-muted">{dateTime(j.createdAt)}</Td>
                  <Td className="text-muted">{j.format}</Td>
                  <Td align="right">{j.rows ?? "—"}</Td>
                  <Td>
                    <Status value={j.status} />
                    {j.error && <span className="block text-xs text-bad">{j.error}</span>}
                  </Td>
                  <Td>
                    {j.status === "DONE" && (
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={async () => {
                          try {
                            saveBlob(await apiBlob(`/exports/${j.id}/file`), `${j.kind.toLowerCase()}.${j.format === "PDF" ? "pdf" : "xlsx"}`);
                          }
                          catch (e) {
                            toast.error(errorMessage(e));
                          }
                        }}
                      >
                        <Download /> Download
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
    </>
  );
}
