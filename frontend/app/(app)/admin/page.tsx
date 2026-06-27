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
  const removeTopic = useTopicsStore((s) => s.remove);

  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const current = findSourceById(sources, currentSourceId);

  const ask = useUiStore((s) => s.ask);
  const flash = useUiStore((s) => s.flash);
  const t = useT();

  const [settings, setSettings] = useState<Settings | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const [resyncing, setResyncing] = useState(false);
  const [resyncDays, setResyncDays] = useState(30);
  const [testingEmail, setTestingEmail] = useState(false);
  const [newTopic, setNewTopic] = useState({ code: "", name: "" });
  const [editingTopicId, setEditingTopicId] = useState<number | null>(null);
  const [editDraft, setEditDraft] = useState({ code: "", name: "" });

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

  async function clearManualArticles() {
    try {
      const r = await apiFetch<{ removed: number }>("/admin/manual-articles", { method: "DELETE" });
      flash(t("admin.clear_manual_done", { n: r.removed }), "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Clear failed", "error");
    }
  }

  async function backfillAll() {
    setBackfilling(true);
    try {
      const results = await apiFetch<SyncResult[]>("/admin/backfill?months=12", { method: "POST" });
      const totalInserted = results.reduce((s, r) => s + r.inserted, 0);
      const totalSkipped = results.reduce((s, r) => s + r.skipped, 0);
      const firstError = results.find((r) => r.error)?.error;
      flash(
        firstError
          ? `Backfill error: ${firstError}`
          : `Backfilled — ${totalInserted} new, ${totalSkipped} skipped`,
        firstError ? "error" : "success"
      );
    } catch (e) {
      flash(e instanceof Error ? e.message : "Backfill failed", "error");
    } finally {
      setBackfilling(false);
    }
  }

  async function deepResync() {
    const days = Math.max(1, Math.min(365, Math.round(resyncDays)));
    setResyncing(true);
    try {
      // The backend runs the resync in the background (it can take minutes) and
      // returns immediately, so we just acknowledge the kickoff here.
      await apiFetch<{ status: string; days: number; topics: number }>(
        `/admin/arxiv/resync?days=${days}`,
        { method: "POST" }
      );
      flash(t("admin.resync_started", { days }), "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Resync failed", "error");
    } finally {
      setResyncing(false);
    }
  }

  async function testSyncEmail() {
    setTestingEmail(true);
    try {
      const r = await apiFetch<{ sent: boolean; to?: string; message: string }>(
        "/admin/notify-test",
        { method: "POST" }
      );
      flash(r.message, r.sent ? "success" : "error");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Test email failed", "error");
    } finally {
      setTestingEmail(false);
    }
  }

  async function syncSource(sourceId: number) {
    setSyncing(true);
    try {
      const r = await apiFetch<SyncResult>(`/papers/sync/${sourceId}`, { method: "POST" });
      flash(
        r.error
          ? cleanSyncError(r.error)
          : `Synced — ${r.inserted} new, ${r.skipped} skipped`,
        r.error ? "error" : "success"
      );
    } catch (e) {
      flash(e instanceof Error ? e.message : "Sync failed", "error");
    } finally {
      setSyncing(false);
    }
  }

  /**
   * Backend wraps errors as "IllegalStateException: <message>" via
   * SyncResult.error. Strip the exception-class prefix so the toast reads
   * cleanly; the original message text (which we now write to be admin-actionable,
   * e.g. "arXiv rate-limit hit (HTTP 429). Wait ~1 minute…") is already what we want.
   */
  function cleanSyncError(raw: string): string {
    const m = raw.match(/^[A-Za-z.$]+Exception:\s*(.+)$/);
    return m ? m[1] : raw;
  }

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">{t("nav.admin")}</h1>

      <TwoFactorSection />

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
          {topics.map((tp) => {
            const isEditing = editingTopicId === tp.id;
            return (
              <li key={tp.id} className="flex items-center gap-2 text-sm py-1">
                {isEditing ? (
                  <>
                    <input
                      value={editDraft.code}
                      onChange={(e) => setEditDraft({ ...editDraft, code: e.target.value })}
                      className="font-mono text-xs w-28 rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-0.5"
                    />
                    <input
                      value={editDraft.name}
                      onChange={(e) => setEditDraft({ ...editDraft, name: e.target.value })}
                      className="flex-1 rounded border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-0.5 text-xs"
                    />
                    <button
                      disabled={!editDraft.code.trim() || !editDraft.name.trim()}
                      onClick={async () => {
                        try {
                          await updateTopic(tp.id, { code: editDraft.code.trim(), name: editDraft.name.trim() });
                          setEditingTopicId(null);
                          flash("Topic updated", "success");
                        } catch (e) {
                          flash(e instanceof Error ? e.message : "Update failed", "error");
                        }
                      }}
                      className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-2 py-0.5 text-xs disabled:opacity-50"
                    >
                      {t("common.save")}
                    </button>
                    <button
                      onClick={() => setEditingTopicId(null)}
                      className="rounded px-2 py-0.5 text-xs text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                    >
                      {t("common.cancel")}
                    </button>
                  </>
                ) : (
                  <>
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
                    <button
                      onClick={() => {
                        setEditDraft({ code: tp.code, name: tp.name });
                        setEditingTopicId(tp.id);
                      }}
                      className="rounded px-2 py-0.5 text-xs text-zinc-600 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                    >
                      {t("common.edit")}
                    </button>
                    <button
                      onClick={() =>
                        ask({
                          title: t("admin.delete_topic_title", { name: tp.name }),
                          message: t("admin.delete_topic_msg"),
                          confirmLabel: t("library.delete"),
                          danger: true,
                          onConfirm: async () => {
                            await removeTopic(tp.id);
                            flash("Topic deleted", "success");
                          },
                        })
                      }
                      className="rounded text-red-600 dark:text-red-300 px-2 py-0.5 text-xs hover:bg-red-50 dark:hover:bg-red-900/30"
                    >
                      {t("library.delete")}
                    </button>
                  </>
                )}
              </li>
            );
          })}
          {topics.length === 0 && <li className="text-sm text-zinc-500">{t("admin.no_topics")}</li>}
        </ul>
      </section>

      {/* Sources */}
      <section className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4 space-y-3">
        <div className="flex items-start justify-between gap-3 flex-wrap">
          <h2 className="font-semibold">{t("admin.data_sources")}</h2>
          <button
            onClick={backfillAll}
            disabled={backfilling || syncing || resyncing}
            title={t("admin.backfill_hint")}
            className="rounded bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1 text-xs hover:opacity-90 disabled:opacity-50"
          >
            {backfilling ? t("admin.backfilling") : t("admin.backfill_12mo")}
          </button>
        </div>
        <p className="text-xs text-zinc-500">{t("admin.backfill_hint")}</p>

        {/* arXiv deep resync — paginate through a wider window and back-fill
            any rows the previous capped runs missed. */}
        <div className="flex items-center gap-2 flex-wrap pt-2 border-t border-zinc-100 dark:border-zinc-800">
          <span className="text-xs text-zinc-500">{t("admin.resync_days_label")}</span>
          <input
            type="number"
            min={1}
            max={365}
            value={resyncDays}
            onChange={(e) => setResyncDays(Number(e.target.value))}
            disabled={resyncing || syncing || backfilling}
            className="w-20 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-2 py-1 text-xs disabled:opacity-50"
          />
          <button
            onClick={deepResync}
            disabled={resyncing || syncing || backfilling}
            title={t("admin.resync_hint")}
            className="rounded bg-purple-600 hover:bg-purple-700 text-white px-3 py-1 text-xs disabled:opacity-50"
          >
            {resyncing ? t("admin.resyncing") : t("admin.resync_button")}
          </button>
        </div>
        <p className="text-xs text-zinc-500">{t("admin.resync_hint")}</p>

        {/* Manually fire the post-sync notification email to verify mail delivery
            without waiting for the 6h cron. */}
        <div className="flex items-center gap-2 flex-wrap pt-2 border-t border-zinc-100 dark:border-zinc-800">
          <button
            onClick={testSyncEmail}
            disabled={testingEmail}
            title={t("admin.notify_test_hint")}
            className="rounded border border-zinc-300 dark:border-zinc-700 px-3 py-1 text-xs hover:bg-zinc-100 dark:hover:bg-zinc-800 disabled:opacity-50"
          >
            {testingEmail ? `${t("admin.notify_test_button")}…` : t("admin.notify_test_button")}
          </button>
          <span className="text-xs text-zinc-500">{t("admin.notify_test_hint")}</span>
        </div>

        <ul className="space-y-1">
          {sources.map((s, idx) => (
            <li key={s.id} className="flex items-center gap-3 text-sm py-1">
              <div className="flex flex-col gap-0.5">
                <button
                  onClick={async () => {
                    if (idx === 0) return;
                    const above = sources[idx - 1];
                    await Promise.all([
                      updateSource(s.id, { displayOrder: above.displayOrder }),
                      updateSource(above.id, { displayOrder: s.displayOrder }),
                    ]);
                    await reloadSources();
                  }}
                  disabled={idx === 0}
                  aria-label={t("admin.move_up")}
                  className="leading-none text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 disabled:opacity-20 disabled:cursor-default"
                >
                  ▲
                </button>
                <button
                  onClick={async () => {
                    if (idx === sources.length - 1) return;
                    const below = sources[idx + 1];
                    await Promise.all([
                      updateSource(s.id, { displayOrder: below.displayOrder }),
                      updateSource(below.id, { displayOrder: s.displayOrder }),
                    ]);
                    await reloadSources();
                  }}
                  disabled={idx === sources.length - 1}
                  aria-label={t("admin.move_down")}
                  className="leading-none text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 disabled:opacity-20 disabled:cursor-default"
                >
                  ▼
                </button>
              </div>
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
                title: t("admin.clear_manual_title"),
                message: t("admin.clear_manual_msg"),
                confirmLabel: t("admin.clear_manual"),
                danger: true,
                onConfirm: clearManualArticles,
              })
            }
            className="rounded bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 text-sm"
          >
            {t("admin.clear_manual")}
          </button>
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

/**
 * Self-contained two-factor enrolment widget. Reads /auth/2fa/status on mount,
 * lets the admin walk through the setup → verify flow (QR rendered via the
 * external qrserver.com service so we don't have to bundle a QR library), and
 * exposes a Disable button when 2FA is on.
 */
function TwoFactorSection() {
  const t = useT();
  const flash = useUiStore((s) => s.flash);
  const ask = useUiStore((s) => s.ask);
  const [status, setStatus] = useState<{ enabled: boolean } | null>(null);
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [verifyCode, setVerifyCode] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiFetch<{ enabled: boolean }>("/auth/2fa/status")
      .then(setStatus)
      .catch(() => setStatus({ enabled: false }));
  }, []);

  async function startSetup() {
    setBusy(true);
    try {
      const res = await apiFetch<{ secret: string; otpauthUri: string }>("/auth/2fa/setup", { method: "POST" });
      setSetup(res);
      setVerifyCode("");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Setup failed", "error");
    } finally {
      setBusy(false);
    }
  }

  async function verifyAndEnable() {
    if (!setup || verifyCode.length !== 6) return;
    setBusy(true);
    try {
      await apiFetch("/auth/2fa/enable", {
        method: "POST",
        body: { secret: setup.secret, code: verifyCode },
      });
      setStatus({ enabled: true });
      setSetup(null);
      setVerifyCode("");
      flash(t("login.2fa_enabled"), "success");
    } catch (e) {
      flash(e instanceof Error ? e.message : "Verification failed", "error");
    } finally {
      setBusy(false);
    }
  }

  function disable() {
    ask({
      title: t("login.2fa_section"),
      message: t("login.2fa_disable") + "?",
      confirmLabel: t("login.2fa_disable"),
      danger: true,
      onConfirm: async () => {
        setBusy(true);
        try {
          await apiFetch("/auth/2fa/disable", { method: "POST" });
          setStatus({ enabled: false });
          flash(t("login.2fa_disabled"), "success");
        } catch (e) {
          flash(e instanceof Error ? e.message : "Disable failed", "error");
        } finally {
          setBusy(false);
        }
      },
    });
  }

  if (status === null) return null;

  return (
    <section className="rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-4 space-y-3">
      <h2 className="font-semibold">{t("login.2fa_section")}</h2>
      <p className="text-sm text-zinc-500">
        {status.enabled ? t("login.2fa_enabled") : t("login.2fa_disabled")}
      </p>

      {!status.enabled && !setup && (
        <button
          type="button"
          onClick={startSetup}
          disabled={busy}
          className="rounded-md bg-zinc-900 dark:bg-zinc-100 dark:text-zinc-900 text-white px-3 py-1.5 text-sm disabled:opacity-50"
        >
          {t("login.2fa_enable")}
        </button>
      )}

      {!status.enabled && setup && (
        <div className="space-y-3">
          <p className="text-sm text-zinc-600 dark:text-zinc-300">{t("login.2fa_scan_hint")}</p>
          {/*
            Render the otpauth URI as a QR via the qrserver public API. The
            URL is short, contains only our own secret bytes, and the request
            goes out as a normal img — no third-party JS bundled.
          */}
          <img
            src={`https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(setup.otpauthUri)}`}
            alt="otpauth QR code"
            width={200}
            height={200}
            className="border border-zinc-200 dark:border-zinc-700 rounded"
          />
          <p className="text-xs text-zinc-500">
            Secret: <code className="font-mono">{setup.secret}</code>
          </p>
          <div className="flex items-center gap-2">
            <input
              type="text"
              inputMode="numeric"
              pattern="\d{6}"
              maxLength={6}
              value={verifyCode}
              onChange={(e) => setVerifyCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
              placeholder="123456"
              className="w-32 rounded-md border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-950 px-3 py-2 text-sm tracking-widest text-center"
            />
            <button
              type="button"
              onClick={verifyAndEnable}
              disabled={busy || verifyCode.length !== 6}
              className="rounded-md bg-emerald-600 hover:bg-emerald-700 text-white px-3 py-1.5 text-sm disabled:opacity-50"
            >
              {t("login.2fa_verify")}
            </button>
            <button
              type="button"
              onClick={() => { setSetup(null); setVerifyCode(""); }}
              disabled={busy}
              className="rounded-md bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 px-3 py-1.5 text-sm"
            >
              {t("login.2fa_cancel")}
            </button>
          </div>
        </div>
      )}

      {status.enabled && (
        <button
          type="button"
          onClick={disable}
          disabled={busy}
          className="rounded-md border border-red-300 dark:border-red-700 text-red-600 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/30 px-3 py-1.5 text-sm disabled:opacity-50"
        >
          {t("login.2fa_disable")}
        </button>
      )}
    </section>
  );
}
