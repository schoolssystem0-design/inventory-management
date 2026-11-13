import { Skeleton } from "@/components/ui/layout";

/** Shown instantly while a page loads, so a tap always feels answered. */
export default function Loading() {
  return (
    <div className="space-y-4" aria-busy="true" aria-label="Loading">
      <Skeleton className="h-8 w-56" />
      <Skeleton className="h-4 w-80" />
      <Skeleton className="h-64" />
    </div>
  );
}
