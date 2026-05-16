export type Role = "USER" | "ADMIN";
export type SortMode = "NEWEST" | "OLDEST" | "TOPIC" | "RELEVANCE";

export interface UserSummary {
  id: number;
  email: string;
  displayName: string | null;
  role: Role;
}

export interface AuthResponse {
  token: string;
  expiresIn: number;
  user: UserSummary;
}

export interface Paper {
  id: number;
  sourceId: number;
  externalId: string;
  title: string;
  authorsJson: string;
  abstract: string;
  introduction: string | null;
  conclusion: string | null;
  url: string | null;
  pdfUrl: string | null;
  pages: number | null;
  topicCode: string | null;
  publishedAt: string;
  fetchedAt: string;
}

export interface PaperPage {
  items: Paper[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Source {
  id: number;
  code: string;
  name: string;
  description: string | null;
  enabled: boolean;
  displayOrder: number;
  createdAt?: string;
}

export interface Topic {
  id: number;
  sourceId: number;
  code: string;
  name: string;
  enabled: boolean;
}

export interface Preferences {
  queryDays: number;
  sortMode: SortMode | string;
  /** Per-source keyword map. Key is the source code (e.g. "arxiv", "hbr"). */
  keywords: Record<string, string[]>;
  currentSourceId: number | null;
  perPage: number;
}

export interface AiSummary {
  id: number;
  summary: string;
  key_points: string[];
  tags: string[];
  difficulty: string | null;
  readingTimeMin: number | null;
  createdAt: string;
}

export interface Favorite {
  id: number;
  paper: Paper;
  note: string | null;
  savedAt: string;
  summary: AiSummary | null;
  cached: boolean;
}

export interface Download {
  id: number;
  paper: Paper;
  filePath: string;
  sizeMB: number;
  downloadedAt: string;
}

export interface Settings {
  defaultDays: number;
  maxResultsPerSync: number;
  autoRefreshIntervalMinutes: number;
}

export interface MonthBucket {
  yearMonth: string;
  byTopic: Record<string, number>;
  total: number;
}

export interface MonthCount {
  yearMonth: string;
  count: number;
}

export interface TopicBreakdown {
  topicCode: string;
  total: number;
  recent5Months: MonthCount[];
}

export interface TrendsResponse {
  metrics: {
    totalPapers: number;
    monthlyAverage: number;
    peakMonth: string;
    activeTopicCount: number;
  };
  months: MonthBucket[];
  topics: TopicBreakdown[];
}

export interface PaperTranslation {
  id: number;
  paperId: number;
  locale: string;
  title: string | null;
  abstract: string | null;
  /** Translated full body. Populated only for manual / URL-imported articles. */
  introduction: string | null;
  createdAt?: string;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  fields?: Record<string, string>;
}

export interface SyncResult {
  sourceCode: string;
  fetched: number;
  inserted: number;
  skipped: number;
  error: string | null;
}
