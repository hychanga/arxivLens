"use client";

import { useEffect } from "react";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("global error", error);
  }, [error]);

  return (
    <main className="flex flex-1 items-center justify-center p-8">
      <div className="max-w-md text-center">
        <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-100">Unexpected error</h1>
        <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400 break-words">{error.message || "Something went wrong."}</p>
        <button
          onClick={reset}
          className="mt-6 rounded-md bg-zinc-900 dark:bg-zinc-100 text-white dark:text-zinc-900 px-4 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          Try again
        </button>
      </div>
    </main>
  );
}
