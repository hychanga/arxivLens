"use client";

import { useEffect } from "react";

export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Forward to a real error tracker (Sentry etc.) here if/when one is configured.
    console.error("(app) route error", error);
  }, [error]);

  return (
    <div className="p-6">
      <div className="max-w-lg rounded-xl border border-red-200 dark:border-red-900 bg-red-50/60 dark:bg-red-900/20 p-5">
        <h2 className="text-base font-semibold text-red-700 dark:text-red-200">Something went wrong</h2>
        <p className="mt-1 text-sm text-red-700/80 dark:text-red-200/80 break-words">{error.message || "Unknown error"}</p>
        {error.digest && <p className="mt-1 text-xs text-red-700/60 dark:text-red-200/60">Ref: {error.digest}</p>}
        <button
          onClick={reset}
          className="mt-4 rounded-md bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400"
        >
          Try again
        </button>
      </div>
    </div>
  );
}
