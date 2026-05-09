import Link from "next/link";

export default function NotFound() {
  return (
    <main className="flex flex-1 items-center justify-center p-8">
      <div className="text-center">
        <p className="text-xs uppercase tracking-wide text-zinc-500">404</p>
        <h1 className="mt-1 text-2xl font-semibold">Page not found</h1>
        <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">
          The page you’re looking for doesn’t exist or has moved.
        </p>
        <Link
          href="/feed"
          className="mt-6 inline-block rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-4 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          Back to Feed
        </Link>
      </div>
    </main>
  );
}
