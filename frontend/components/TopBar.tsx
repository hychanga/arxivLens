"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuthStore } from "@/store/auth";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { usePreferencesStore } from "@/store/preferences";
import { useUiStore } from "@/store/ui";
import { useT } from "@/lib/i18n";
import LocaleSelector from "./LocaleSelector";
import ThemeToggle from "./ThemeToggle";
import type { Source } from "@/types";

export default function TopBar() {
  const router = useRouter();
  const logout = useAuthStore((s) => s.logout);
  const user = useAuthStore((s) => s.user);

  const sources = useSourcesStore((s) => s.items);
  const createSource = useSourcesStore((s) => s.create);

  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const patchPrefs = usePreferencesStore((s) => s.patch);

  const flash = useUiStore((s) => s.flash);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const t = useT();

  const [adding, setAdding] = useState(false);
  const [newCode, setNewCode] = useState("");
  const [newName, setNewName] = useState("");

  const enabled = sources.filter((s) => s.enabled);
  const current = findSourceById(sources, currentSourceId);

  async function switchTo(s: Source) {
    if (s.id === currentSourceId) return;
    try {
      await patchPrefs({ currentSourceId: s.id });
    } catch (e) {
      flash(e instanceof Error ? e.message : "Switch failed", "error");
    }
  }

  async function handleAdd() {
    if (!newCode.trim() || !newName.trim()) return;
    try {
      await createSource({ code: newCode.trim(), name: newName.trim() });
      setNewCode("");
      setNewName("");
      setAdding(false);
      flash("Source added", "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Add failed", "error");
    }
  }

  return (
    <header className="border-b border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
      <div className="flex items-center gap-2 sm:gap-3 px-3 sm:px-4 py-2 flex-wrap">
        <button
          type="button"
          onClick={toggleSidebar}
          aria-label={t("topbar.open_sidebar")}
          aria-controls="app-sidebar"
          className="md:hidden rounded-md p-1.5 text-zinc-600 hover:bg-zinc-100 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          {/* Plain Unicode hamburger keeps the bundle slim — swap for an SVG icon if/when iconography ships. */}
          <span aria-hidden>☰</span>
        </button>

        <span className="font-semibold mr-1 sm:mr-2">arXivLens</span>

        <nav aria-label="Sources" className="flex items-center gap-1 flex-wrap">
          {enabled.map((s) => (
            <button
              key={s.id}
              type="button"
              onClick={() => switchTo(s)}
              aria-current={current?.id === s.id ? "page" : undefined}
              className={`rounded-md px-3 py-1.5 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                current?.id === s.id
                  ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                  : "text-zinc-600 hover:bg-zinc-100 dark:hover:bg-zinc-800"
              }`}
            >
              {s.name}
            </button>
          ))}
          {!adding && user?.role === "ADMIN" && (
            <button
              type="button"
              onClick={() => setAdding(true)}
              className="rounded-md px-2 py-1.5 text-sm text-zinc-500 border border-dashed border-zinc-300 dark:border-zinc-700 hover:bg-zinc-50 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
              {t("topbar.add_source")}
            </button>
          )}
          {adding && (
            <span className="flex items-center gap-1">
              <input
                value={newCode}
                onChange={(e) => setNewCode(e.target.value.toLowerCase())}
                placeholder="code"
                aria-label="Source code"
                className="w-24 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              />
              <input
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="display name"
                aria-label="Source display name"
                className="w-44 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
              />
              <button type="button" onClick={handleAdd} className="rounded-md bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500">
                {t("common.save")}
              </button>
              <button type="button" onClick={() => setAdding(false)} className="rounded-md bg-zinc-100 dark:bg-zinc-800 px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500">
                {t("common.cancel")}
              </button>
            </span>
          )}
        </nav>

        <div className="ml-auto flex items-center gap-2 sm:gap-3">
          <ThemeToggle />
          <LocaleSelector />
          {user && (
            <span className="hidden sm:inline-flex items-center text-sm text-zinc-600 dark:text-zinc-300">
              <span aria-hidden className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-zinc-200 dark:bg-zinc-700 text-xs font-medium mr-1">
                {(user.displayName ?? user.email).slice(0, 1).toUpperCase()}
              </span>
              <span className="truncate max-w-[10rem]">{user.displayName ?? user.email}</span>
            </span>
          )}
          <button
            type="button"
            onClick={() => {
              logout();
              router.replace("/login");
            }}
            className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 rounded-md px-1.5 py-1"
          >
            {t("topbar.signout")}
          </button>
        </div>
      </div>
    </header>
  );
}
