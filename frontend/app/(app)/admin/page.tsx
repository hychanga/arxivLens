"use client";

import { useEffect, useState } from "react";
import { useAuthStore } from "@/store/auth";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useTopicsStore } from "@/store/topics";
import { usePreferencesStore } from "@/store/preferences";
import { useUiStore } from "@/store/ui";
import { apiFetch } from "@/lib/api";
import { useT } from "@/lib/i18n";
import type { Settings, SyncResult } from "@/types";

export default function AdminPage() {
  const role = useAuthStore((s) => s.user?.role);
  const sources = useSourcesStore((s) => s.items);
  const updateSource = useSourcesStore((s) => s.update);
  const removeSource = useSourcesStore((s) => s.remove);
  const reloadSources = useSourcesStore((s) => s.load);

  const topicsBySource = useTopicsStore((s) => s.bySource);
  const loadTopicsFor = useTopicsStore((s) => s.loadFor);
  const updateTopic = useTopicsStore((s) => s.update);
  const createTopic = useTopicsStore((s) => s.create);

  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const current = findSourceById(sources, currentSourceId);

  const ask = useUiStore((s) => s.ask);
  const flash = useUiStore((s) => s.flash);
  const t = useT();

  const [settings, setSettings] = useState<Settings | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [newTopic, setNewTopic] = useState({ code: "", name: "" });

  useEffect(() => {
    if (role !== "ADMIN") return;
    apiFetch<Settings>("/admin/settings")
      .then(setSettings)
      .catch((e: unknown) => flash(e instanceof Error ? e.message : "Load settings failed", "error"));
  }, [role, flash]);

  useEffect(() => {
    if (current) void loadTopicsFor(current.id);
  }, [current, loadTopicsFor]);

  if (role !== "ADMIN") {
    return (
      <p className="text-sm text-zinc-500">
        {t("admin.admin_only")}
      </p>
    );
  }

  const topics = current ? topicsBySource[current.id] ?? [] : [];

  async function patchSettings(changes: Partial<Settings>) {
    try {
      const next = await apiFetch<Settings>("/admin/settings", {
        method: "PUT",
        body: changes,
      });
      setSettings(next);
      flash("Settings saved", "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Save failed", "error");
    }
  }

  async function resetSettings() {
    try {
      const next = await apiFetch<Settings>("/admin/settings/reset", { method: "POST" });
      setSettings(next);
      flash("Settings reset", "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Reset failed", "error");
    }
  }

  async function clearPapers() {
    try {
      const r = await apiFetch<{ removed: number }>("/admin/papers", { method: "DELETE" });
      flash(`Cleared ${r.removed} papers`, "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Clear failed", "error");
    }
  }

  async function syncSource(sourceId: number) {
    setSyncing(true);
    try {
      const r = await apiFetch<SyncResult>(`/papers/sync/${sourceId}`, { method: "POST" });
      flash(
        r.error
          ? `Sync error: ${r.error}`
          : `Synced — ${r.inserted} new, ${r.skipped} skipped`,
        r.error ? "error" : "success"
      );
    } catch (e) {
      flash(e instanceof Error ? e.message : "Sync failed", "error");
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">{t("nav.admin")}</h1>

      {/* Settings */}
      <section className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4 space-y-3">
        <h2 className="font-semibold">{t("admin.search_defaults")}</h2>
        {!settings ? (
          <p className="text-sm text-zinc-500">{t("common.loading")}</p>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-sm">
            <NumberField
              label={t("admin.default_days")}
              saveLabel={t("common.save")}
              value={settings.defaultDays}
              min={1} max={365}
              onCommit={(v) => patchSettings({ defaultDays: v })}
            />
            <NumberField
              label={t("admin.max_results")}
              saveLabel={t("common.save")}
              value={settings.maxResultsPerSync}
              min={1} max={2000}
              onCommit={(v) => patchSettings({ maxResultsPerSync: v })}
            />
            <NumberField
              label={t("admin.refresh_interval")}
              saveLabel={t("common.save")}
              value={settings.autoRefreshIntervalMinutes}
              min={15} max={1440}
              onCommit={(v) => patchSettings({ autoRefreshIntervalMinutes: v })}
            />
          </div>
        )}
      </section>

      {/* Topics for current source */}
      <section className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4 space-y-3">
        <h2 className="font-semibold">{t("admin.topics_for", { source: current?.name ?? "—" })}</h2>
        <div className="flex gap-2">
          <input
            value={newTopic.code}
            onChange={(e) => setNewTopic({ ...newTopic, code: e.target.value })}
            placeholder={t("admin.topic_code_placeholder")}
            className="rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm"
          />
          <input
            value={newTopic.name}
            onChange={(e) => setNewTopic({ ...newTopic, name: e.target.value })}
            placeholder={t("admin.topic_name_placeholder")}
            className="flex-1 rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm"
          />
          <button
            disabled={!current || !newTopic.code.trim() || !newTopic.name.trim()}
            onClick={async () => {
              if (!current) return;
              try {
                await createTopic({ sourceId: current.id, code: newTopic.code.trim(), name: newTopic.name.trim() });
                setNewTopic({ code: "", name: "" });
                flash("Topic added", "success");
              } catch (e) {
                flash(e instanceof Error ? e.message : "Add failed", "error");
              }
            }}
            className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1 text-sm disabled:opacity-50"
          >
            {t("common.add")}
          </button>
        </div>
        <ul className="space-y-1">
          {topics.map((tp) => (
            <li key={tp.id} className="flex items-center gap-3 text-sm py-1">
              <code className="font-mono text-xs text-zinc-500 w-24 truncate">{tp.code}</code>
              <span className="flex-1 truncate">{tp.name}</span>
              <label className="text-xs flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={tp.enabled}
                  onChange={(e) => updateTopic(tp.id, { enabled: e.target.checked })}
                />
                {t("admin.enabled")}
              </label>
            </li>
          ))}
          {topics.length === 0 && <li className="text-sm text-zinc-500">{t("admin.no_topics")}</li>}
        </ul>
      </section>

      {/* Sources */}
      <section className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4 space-y-3">
        <h2 className="font-semibold">{t("admin.data_sources")}</h2>
        <ul className="space-y-1">
          {sources.map((s) => (
            <li key={s.id} className="flex items-center gap-3 text-sm py-1">
              <code className="font-mono text-xs text-zinc-500 w-20 truncate">{s.code}</code>
              <span className="flex-1 truncate">{s.name}</span>
              <button
                onClick={() => syncSource(s.id)}
                disabled={syncing}
                className="rounded bg-zinc-100 dark:bg-zinc-800 px-2 py-1 text-xs hover:bg-zinc-200 dark:hover:bg-zinc-700 disabled:opacity-50"
              >
                {t("admin.sync_now")}
              </button>
              <label className="text-xs flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={s.enabled}
                  onChange={(e) => updateSource(s.id, { enabled: e.target.checked })}
                />
                {t("admin.enabled")}
              </label>
              <button
                onClick={() =>
                  ask({
                    title: t("admin.delete_source_title", { code: s.code }),
                    message: t("admin.delete_source_msg"),
                    confirmLabel: t("library.delete"),
                    danger: true,
                    onConfirm: async () => {
                      await removeSource(s.id);
                      await reloadSources();
                      flash("Deleted", "success");
                    },
                  })
                }
                className="rounded text-red-600 dark:text-red-300 px-2 py-1 text-xs hover:bg-red-50 dark:hover:bg-red-900/30"
              >
                {t("library.delete")}
              </button>
            </li>
          ))}
        </ul>
      </section>

      {/* Danger zone */}
      <section className="rounded-lg border border-red-300 dark:border-red-800 bg-red-50/50 dark:bg-red-900/10 p-4 space-y-3">
        <h2 className="font-semibold text-red-700 dark:text-red-300">{t("admin.danger_zone")}</h2>
        <div className="flex flex-wrap gap-2">
          <button
            onClick={() =>
              ask({
                title: t("admin.clear_paper_cache_title"),
                message: t("admin.clear_paper_cache_msg"),
                confirmLabel: t("admin.clear_paper_cache"),
                danger: true,
                onConfirm: clearPapers,
              })
            }
            className="rounded bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 text-sm"
          >
            {t("admin.clear_paper_cache")}
          </button>
          <button
            onClick={() =>
              ask({
                title: t("admin.reset_settings_title"),
                message: t("admin.reset_settings_msg"),
                confirmLabel: t("admin.reset_settings"),
                danger: true,
                onConfirm: resetSettings,
              })
            }
            className="rounded bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 text-sm"
          >
            {t("admin.reset_settings")}
          </button>
        </div>
      </section>
    </div>
  );
}

function NumberField({
  label,
  saveLabel,
  value,
  min,
  max,
  onCommit,
}: {
  label: string;
  saveLabel: string;
  value: number;
  min: number;
  max: number;
  onCommit: (v: number) => void;
}) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const dirty = draft !== value;
  return (
    <label className="block">
      <span className="block text-xs text-zinc-500 mb-1">{label}</span>
      <span className="flex gap-2">
        <input
          type="number"
          value={draft}
          min={min}
          max={max}
          onChange={(e) => setDraft(Number(e.target.value))}
          className="w-full rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-sm"
        />
        <button
          disabled={!dirty}
          onClick={() => onCommit(draft)}
          className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-2 py-1 text-sm disabled:opacity-30"
        >
          {saveLabel}
        </button>
      </span>
    </label>
  );
}
