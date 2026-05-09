"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/store/auth";
import { usePreferencesStore } from "@/store/preferences";
import { useSourcesStore, findSourceById } from "@/store/sources";
import { useTopicsStore } from "@/store/topics";
import { useFavoritesStore } from "@/store/favorites";
import { useDownloadsStore } from "@/store/downloads";
import { usePapersStore } from "@/store/papers";
import { useLocaleStore } from "@/store/locale";
import TopBar from "@/components/TopBar";
import Sidebar from "@/components/Sidebar";
import ConfirmDialog from "@/components/ConfirmDialog";
import PaperPreviewModal from "@/components/PaperPreviewModal";
import Toast from "@/components/Toast";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { token, hydrate } = useAuthStore();

  const loadPrefs = usePreferencesStore((s) => s.load);
  const prefsLoaded = usePreferencesStore((s) => s.loaded);
  const currentSourceId = usePreferencesStore((s) => s.currentSourceId);
  const queryDays = usePreferencesStore((s) => s.queryDays);
  const perPage = usePreferencesStore((s) => s.perPage);

  const sources = useSourcesStore((s) => s.items);
  const sourcesLoaded = useSourcesStore((s) => s.loaded);
  const loadSources = useSourcesStore((s) => s.load);

  const loadTopicsFor = useTopicsStore((s) => s.loadFor);
  const topicsBySource = useTopicsStore((s) => s.bySource);

  const loadFavorites = useFavoritesStore((s) => s.load);
  const favLoaded = useFavoritesStore((s) => s.loaded);

  const loadDownloads = useDownloadsStore((s) => s.load);
  const dlLoaded = useDownloadsStore((s) => s.loaded);

  const fetchPapers = usePapersStore((s) => s.fetch);
  const setSource = usePapersStore((s) => s.setSource);
  const setDays = usePapersStore((s) => s.setDays);
  const setSize = usePapersStore((s) => s.setSize);
  const papersSource = usePapersStore((s) => s.source);
  const papersDays = usePapersStore((s) => s.days);
  const papersSize = usePapersStore((s) => s.size);
  const papersPage = usePapersStore((s) => s.page);
  const papersTopic = usePapersStore((s) => s.topic);

  const hydrateLocale = useLocaleStore((s) => s.hydrate);

  // Hydrate auth + locale from localStorage on mount.
  useEffect(() => {
    hydrate();
    hydrateLocale();
  }, [hydrate, hydrateLocale]);

  // Auth guard.
  useEffect(() => {
    if (token === null) router.replace("/login");
  }, [token, router]);

  // Initial loads (only when authed).
  useEffect(() => {
    if (!token) return;
    if (!sourcesLoaded) void loadSources();
    if (!prefsLoaded) void loadPrefs();
    if (!favLoaded) void loadFavorites();
    if (!dlLoaded) void loadDownloads();
  }, [token, sourcesLoaded, prefsLoaded, favLoaded, dlLoaded, loadSources, loadPrefs, loadFavorites, loadDownloads]);

  // When prefs/sources both ready, sync papersStore + load topics for current source.
  useEffect(() => {
    if (!sourcesLoaded || !prefsLoaded) return;
    const current = findSourceById(sources, currentSourceId) ?? sources.find((s) => s.enabled);
    if (current) {
      if (current.code !== papersSource) setSource(current.code);
      if (!topicsBySource[current.id]) void loadTopicsFor(current.id);
    }
    if (queryDays !== papersDays) setDays(queryDays);
    if (perPage !== papersSize) setSize(perPage);
  }, [
    sourcesLoaded,
    prefsLoaded,
    sources,
    currentSourceId,
    queryDays,
    perPage,
    papersSource,
    papersDays,
    papersSize,
    setSource,
    setDays,
    setSize,
    loadTopicsFor,
    topicsBySource,
  ]);

  // Refetch papers whenever the inputs change.
  useEffect(() => {
    if (!sourcesLoaded || !prefsLoaded) return;
    void fetchPapers();
  }, [sourcesLoaded, prefsLoaded, papersSource, papersDays, papersSize, papersPage, papersTopic, fetchPapers]);

  if (!token) return null;

  return (
    <div className="flex flex-col flex-1 min-h-screen">
      <TopBar />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
      <ConfirmDialog />
      <PaperPreviewModal />
      <Toast />
    </div>
  );
}
