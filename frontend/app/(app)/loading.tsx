export default function Loading() {
  return (
    <div className="p-6 space-y-3" role="status" aria-busy="true" aria-live="polite">
      <div className="h-5 w-32 bg-zinc-200 dark:bg-zinc-800 rounded animate-pulse" />
      <div className="h-24 bg-zinc-200 dark:bg-zinc-800 rounded animate-pulse" />
      <div className="h-24 bg-zinc-200 dark:bg-zinc-800 rounded animate-pulse" />
      <div className="h-24 bg-zinc-200 dark:bg-zinc-800 rounded animate-pulse" />
      <span className="sr-only">Loading…</span>
    </div>
  );
}
