"use client";

import { useEffect, useRef } from "react";
import { useUiStore } from "@/store/ui";
import { useT } from "@/lib/i18n";

export default function ConfirmDialog() {
  const confirm = useUiStore((s) => s.confirm);
  const close = useUiStore((s) => s.closeConfirm);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const t = useT();

  useEffect(() => {
    if (!confirm.open) return;
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [confirm.open, close]);

  if (!confirm.open) return null;

  const danger = confirm.danger ?? true;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={close}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 shadow-xl p-6"
      >
        <div className="flex items-start gap-3">
          {danger && (
            <span aria-hidden className="mt-0.5 inline-flex h-6 w-6 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-300 font-bold">
              !
            </span>
          )}
          <div className="flex-1 min-w-0">
            <h2 id="confirm-title" className="text-lg font-semibold mb-1">{confirm.title}</h2>
            <p className="text-sm text-zinc-600 dark:text-zinc-400 whitespace-pre-line">{confirm.message}</p>
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            ref={cancelRef}
            onClick={close}
            className="rounded-md px-4 py-2 text-sm bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
          >
            {t("common.cancel")}
          </button>
          <button
            onClick={async () => {
              try {
                await confirm.onConfirm();
              } finally {
                close();
              }
            }}
            className={
              "rounded-md px-4 py-2 text-sm text-white " +
              (danger ? "bg-red-600 hover:bg-red-700" : "bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 hover:opacity-90")
            }
          >
            {confirm.confirmLabel ?? t("common.confirm")}
          </button>
        </div>
      </div>
    </div>
  );
}
