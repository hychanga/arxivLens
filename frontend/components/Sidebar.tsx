"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useFavoritesStore } from "@/store/favorites";
import { useDownloadsStore } from "@/store/downloads";
import { useTopicsStore } from "@/store/topics";
import { usePreferencesStore } from "@/store/preferences";
import { usePapersStore } from "@/store/papers";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useUiStore } from "@/store/ui";
import { useT } from "@/lib/i18n";
import DaysControl from "./DaysControl";
import KeywordEditor from "./KeywordEditor";

const NAV: { href: string; tKey: string }[] = [
  { href: "/feed",      tKey: "nav.latest"    },
  { href: "/favorites", tKey: "nav.favorites" },
  { href: "/library",   tKey: "nav.library"   },
  { href: "/trends",    tKey: "nav.trends"    },
  { href: "/admin",     tKey: "nav.admin"     },
];

export default function Sidebar() {
  const pathname = usePathname();
  const sources = useSourcesStore((s) => s.items);
  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const queryDays = usePreferencesStore((s) => s.queryDays);
  const keywordsBySource = usePreferencesStore((s) => s.keywords);
  const patchPrefs = usePreferencesStore((s) => s.patch);
  const patchLocal = usePreferencesStore((s) => s.patchLocal);

  // Badge counts reflect the currently-selected source so they stay in sync with
  // the per-source filtering on /favorites and /library.
  const favCount = useFavoritesStore((s) =>
    currentSourceId == null
      ? s.items.length
      : s.items.filter((f) => f.paper.sourceId === currentSourceId).length
  );
  const dlCount = useDownloadsStore((s) =>
    currentSourceId == null
      ? s.items.length
      : s.items.filter((d) => d.paper.sourceId === currentSourceId).length
  );

  const topicsBySource = useTopicsStore((s) => s.bySource);
  const topic = usePapersStore((s) => s.topic);
  const setTopic = usePapersStore((s) => s.setTopic);

  const sidebarOpen = useUiStore((s) => s.sidebarOpen);
  const setSidebarOpen = useUiStore((s) => s.setSidebarOpen);
  const t = useT();

  const current = findSourceById(sources, currentSourceId);
  const topics = current ? topicsBySource[current.id] ?? [] : [];
  const currentSourceCode = current?.code ?? null;
  const keywordsForSource = currentSourceCode ? keywordsBySource[currentSourceCode] ?? [] : [];

  return (
    <>
      {/* Mobile backdrop. Desktop never sees this — the aside lives inside the flex flow. */}
      {sidebarOpen && (
        <div
          aria-hidden="true"
          onClick={() => setSidebarOpen(false)}
          className="fixed inset-0 z-30 bg-black/40 md:hidden"
        />
      )}

      <aside
        id="app-sidebar"
        aria-label="Sidebar"
        className={`
          fixed inset-y-0 left-0 z-40 w-72
          border-r border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900
          p-4 text-sm overflow-y-auto
          transform transition-transform duration-200
          ${sidebarOpen ? "translate-x-0" : "-translate-x-full"}
          md:static md:inset-auto md:translate-x-0 md:transition-none md:shrink-0
        `}
      >
        <div className="md:hidden flex items-center justify-between mb-3">
          <h2 className="font-semibold">{current?.name ?? "—"}</h2>
          <button
            type="button"
            aria-label={t("common.close")}
            onClick={() => setSidebarOpen(false)}
            className="rounded-md p-1 text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            ×
          </button>
        </div>
        <h2 className="hidden md:block font-semibold">{current?.name ?? "—"}</h2>
        {current?.description && (
          <p className="text-zinc-500 text-xs mt-0.5 mb-3">{current.description}</p>
        )}

        <nav aria-label={t("nav.label_main")} className="space-y-1 mb-4">
          {NAV.map((n) => {
            const active = pathname === n.href;
            const badge = n.href === "/favorites" ? favCount : n.href === "/library" ? dlCount : null;
            return (
              <Link
                key={n.href}
                href={n.href}
                aria-current={active ? "page" : undefined}
                onClick={() => setSidebarOpen(false)}
                className={`flex items-center justify-between rounded-md px-2 py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                  active
                    ? "bg-zinc-100 dark:bg-zinc-800 font-medium"
                    : "text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800/60"
                }`}
              >
                <span>{t(n.tKey)}</span>
                {badge != null && badge > 0 && (
                  <span className="rounded-full bg-zinc-200 dark:bg-zinc-700 text-xs px-2 py-0.5">{badge}</span>
                )}
              </Link>
            );
          })}
        </nav>

        <div className="space-y-4">
          <DaysControl days={queryDays} onApply={(d) => patchPrefs({ queryDays: d })} />

          <div>
            <div className="text-xs uppercase tracking-wide text-zinc-500 mb-1 flex items-center justify-between">
              <span>{t("sidebar.keywords")}</span>
              {currentSourceCode && (
                <span className="text-[10px] font-normal normal-case tracking-normal text-zinc-400">
                  {t("sidebar.keywords_for", { source: currentSourceCode })}
                </span>
              )}
            </div>
            {currentSourceCode ? (
              <KeywordEditor
                keywords={keywordsForSource}
                onChange={(kw) => {
                  // Keep other sources' keyword lists intact when editing this source's list.
                  const next = { ...keywordsBySource, [currentSourceCode]: kw };
                  patchLocal({ keywords: next });
                }}
              />
            ) : (
              <p className="text-xs text-zinc-500">{t("sidebar.no_source_for_keywords")}</p>
            )}
          </div>

          {topics.length > 0 && (
            <div>
              <div className="text-xs uppercase tracking-wide text-zinc-500 mb-1">{t("sidebar.filter_topic")}</div>
              <div className="flex flex-wrap gap-1">
                <button
                  type="button"
                  onClick={() => setTopic(null)}
                  className={`rounded-md px-2 py-0.5 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                    !topic ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900" : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
                  }`}
                >
                  {t("sidebar.all")}
                </button>
                {topics
                  .filter((t) => t.enabled)
                  .map((t) => (
                    <button
                      type="button"
                      key={t.id}
                      onClick={() => setTopic(t.code)}
                      className={`rounded-md px-2 py-0.5 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                        topic === t.code
                          ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
                          : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700"
                      }`}
                    >
                      {t.code}
                    </button>
                  ))}
              </div>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}
