# arxivLens — 設計需求清單

**整理日期：** 2026-05-07  
**技術堆疊：** Next.js 15 + Spring Boot 3 + MySQL 8  
**部署方式：** Docker Compose

---

## 一、整體架構需求

- 全端 Web 應用程式，前後端分離架構
- 前端：Next.js 最新版（React 19、TypeScript、Tailwind CSS、Zustand 狀態管理）
- 後端：Spring Boot 4.0.6（Java 25、JPA、Spring Security、JWT 認證）
- 資料庫：MySQL
- 可透過 Docker Compose 一鍵啟動所有服務

---

## 二、資料來源

| 來源 | 說明 |
|---|---|
| **arXiv** | 研究論文預印本，每 6 小時自動同步 |
| **哈佛商業評論（HBR）** | 管理與領導洞察，RSS 來源 |

- 資料來源可在 Admin 頁面管理（新增、啟用/停用）
- 每個來源有獨立的主題分類（arXiv 用 Topics、HBR 用 Categories）
- 啟動時自動 seed 示範資料

---

## 三、版面佈局

### 頂部來源 Tab 列
- 並排顯示所有已啟用的資料來源（arXiv、哈佛商業評論）
- 右側顯示登入使用者頭像、名稱、登出按鈕（不另設選單列）
- 「+ Add source」按鈕可新增資料來源

### 左側 Sidebar
- 顯示目前來源的名稱與描述
- 導覽項目：最新文章 / 我的收藏 / 已下載 / 12 個月趨勢 / 管理設定
- 收藏數與下載數以徽章顯示
- 主題篩選器（點擊篩選文章）

### 主內容區
- 根據 Sidebar 選項顯示對應的 Panel

---

## 四、Feed（最新文章）Panel

### 搜尋期間設定
- 滑桿（1～365 天），搭配快速選擇按鈕（7d / 30d / 90d / 6mo / 1yr）
- 點擊快速選擇按鈕 → **立即套用**（不需按 Apply）
- 拖動滑桿 → 出現 Apply 按鈕才套用
- 滑桿值驗證：限制在 1～365 之間、自動四捨五入
- 套用後顯示 ✓ Applied 提示（2 秒後消失）
- 期間橫幅顯示：「目前顯示 N 篇文章，套用期間 X 天」

### 關鍵字設定
- 可新增**無限數量**的關鍵字（無上限）
- 關鍵字以標籤（Chip）方式顯示
- 每個關鍵字顯示優先順序編號（#1、#2、#3…）
- 提供 ▲ / ▼ 按鈕調整優先順序
- 先後順序決定配對權重（#1 權重最高=10，依序遞減）
- 點擊 × 刪除關鍵字
- 用不同顏色區分優先高低（前 1/3 綠色、中 1/3 藍色、後 1/3 橙色）

### 相關性評分
- 根據關鍵字配對計算 1～10 分
- 計分公式：標題命中×3倍 + 作者命中×2倍 + 摘要命中×1倍，乘以該關鍵字優先權重
- 分數以彩色徽章顯示（≥8 綠色、≥5 藍色、≥3 橙色、<3 灰色）

### 全選與排序
- 全選 Checkbox（支援 indeterminate 狀態）
- 已加入收藏的文章顯示綠色圓點，**不顯示** Checkbox
- 排序方式：最新 / 最舊 / 依主題 / 依配對度
- 「依配對度」排序只在有關鍵字時顯示，用紫色標示
- 選取文章後出現「Save N ★」按鈕

### 文章卡片
- 顯示：主題標籤、日期、★ saved 標示、cached 標示、配對分數
- 點擊展開顯示摘要和 arXiv 連結
- 再次點擊收合

### 分頁功能
- 分頁列：顯示文章範圍、頁碼按鈕（含省略符號）、上下頁
- 「Go to」輸入框直接跳頁（按 Enter 觸發）
- 「Per page」下拉選單（10 / 50 / 100），位置在「Go to」右側
- 頁面切換時重置到第一頁

### 統計列（底部）
- 文章數 / 主題數 / 已套用天數 / 已選取數 / 目前頁碼 / ≥5/10 配對數

---

## 五、Favorites（我的收藏）Panel

- 顯示所有已收藏文章，含主題標籤、來源、日期
- 已快取文章顯示 cached 標籤、有筆記顯示 note 標籤、有 AI 摘要顯示 AI summary 標籤

### 每篇文章的操作按鈕
| 按鈕 | 功能 |
|---|---|
| **Preview** | 開啟預覽 Modal，顯示摘要、引言、結論 |
| **Open cached** | 在新分頁開啟快取 PDF（使用 `window.open`） |
| **Download PDF** | 下載 PDF，顯示進度條 |
| **Generate summary** | 呼叫 AI 產生摘要（摘要 / 重點 / 標籤 / 難度 / 閱讀時間） |
| **Add note / Edit note** | 新增或編輯個人筆記 |
| **Remove** | 移除收藏（需確認對話框） |

### 確認對話框（Confirm Dialog）
- Remove 按鈕 → 顯示確認框，說明「筆記和 AI 摘要也會一併刪除」
- 取消鍵預設獲得 focus，按 Escape 可關閉
- 使用紅色三角警告圖示

### Download all 按鈕
- 一次下載所有尚未快取的收藏文章

---

## 六、Library（已下載）Panel

- 從 `downloads` 狀態直接渲染，**不依賴** Favorites 交叉查詢
- 每個文件卡片顯示：PDF 圖示、標題、作者、主題標籤、檔案大小、下載日期、arXiv 編號
- 若對應收藏有筆記，在卡片內一併顯示
- **Read paper** 按鈕 → 開啟預覽 Modal（帶 isCached=true，顯示「Open cached PDF」）
- **Delete** 按鈕 → 確認對話框（說明可從收藏重新下載）
- **Clear all** 按鈕 → 確認對話框（說明所有文件數量）

---

## 七、Trends（趨勢）Panel

- 顯示過去 12 個月的文章發布數量
- 四個指標卡：總數 / 月均 / 最高月份 / 活躍主題數
- 堆疊長條圖，按月份和主題分組
- 各主題過去 5 個月的細項數據（水平進度條）

---

## 八、Admin（管理設定）Panel

### 搜尋預設值
- 預設查詢天數（下拉選單）
- 每次同步最大筆數
- 自動刷新間隔

### 主題管理
- 顯示目前資料來源的主題清單（可啟用/停用切換）
- 可新增自訂主題
- 切換資料來源時顯示對應的主題

### arXiv 分類設定
- 僅在 arXiv 來源時顯示
- 可點擊啟用/停用 arXiv 分類（cs.AI / cs.LG / cs.CL 等）

### 資料來源管理
- 列出所有已設定的資料來源
- 每個來源可切換啟用/停用狀態
- 可新增資料來源

### 危險操作區
- 清除論文快取（下次同步時重新抓取）
- 重置所有設定至預設值

---

## 九、預覽 Modal（Paper Preview Modal）

- 在收藏和圖書館都可開啟
- 顯示：主題標籤、來源、日期、快取標示、標題、作者
- 內容分區：摘要（必填）/ 引言節選（若有）/ 結論（若有）
- 底部資訊列：arXiv 編號、頁數、檔案大小
- 底部按鈕：
  - 已快取 → 「Open cached PDF」（開新分頁）
  - 未快取 → 「Download & save」
  - 「Open on arXiv」（連結到 arXiv 頁面）
  - 「Close」
- 點擊背景或按 Escape 關閉

---

## 十、使用者認證

- 登入頁面：Email + 密碼
- Google / GitHub OAuth 分頁（前端模擬）
- JWT Token 認證，儲存在 localStorage
- 每次 API 請求自動附加 Bearer Token
- 401 錯誤時自動導回登入頁

### 使用者偏好設定持久化
- 登入後從伺服器載入偏好設定（查詢天數、排序方式、關鍵字清單）
- 偏好設定任何變動時自動儲存至後端（`PATCH /api/preferences`）
- 下次登入自動恢復設定

---

## 十一、資料庫 Schema

| 資料表 | 說明 |
|---|---|
| `settings` | 全域設定（單筆資料） |
| `data_sources` | 資料來源（arXiv、HBR 等） |
| `topics` | 主題分類（依來源分組） |
| `users` | 使用者帳號（支援 email/password 與 OAuth） |
| `user_preferences` | 使用者偏好（關鍵字儲存為 JSON 陣列，**順序即優先順序**） |
| `papers` | 文章（`abstract_text` 序列化為 `"abstract"`） |
| `favorites` | 收藏（含筆記） |
| `ai_summaries` | AI 摘要（`key_points` / `tags` 儲存為 JSON 陣列字串） |
| `downloads` | 已下載紀錄 |

### 重要序列化對應
- `Paper.abstractText` → JSON 欄位名稱 `"abstract"`
- `Paper.pageCount` → JSON 欄位名稱 `"pages"`
- `Download.sizeMb` → JSON 欄位名稱 `"sizeMB"`
- `AiSummary.keyPoints` / `tags` → 儲存 JSON 字串，透過 getter 解析為 `List<String>`

---

## 十二、API 端點

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/auth/login` | 登入 |
| POST | `/api/auth/register` | 註冊 |
| GET | `/api/preferences` | 取得使用者偏好 |
| PATCH | `/api/preferences` | 更新使用者偏好 |
| GET | `/api/papers` | 取得文章（依來源、天數、主題篩選） |
| POST | `/api/papers/sync/{sourceId}` | 手動觸發同步 |
| GET | `/api/favorites` | 取得收藏清單 |
| POST | `/api/favorites` | 新增收藏 |
| DELETE | `/api/favorites/{id}` | 刪除收藏 |
| PUT | `/api/favorites/{id}/note` | 更新筆記 |
| POST | `/api/favorites/{id}/summary` | 產生 AI 摘要 |
| GET | `/api/downloads` | 取得下載清單 |
| POST | `/api/downloads` | 下載文章 |
| DELETE | `/api/downloads/{paperId}` | 刪除下載 |
| DELETE | `/api/downloads` | 清除全部下載 |
| GET | `/api/trends` | 趨勢資料 |
| GET/POST/PUT/DELETE | `/api/sources` | 資料來源管理 |
| GET/POST/PUT/DELETE | `/api/topics` | 主題管理 |
| GET/PUT | `/api/admin/settings` | 管理設定 |

---

## 十三、已修復的 Bug

| 問題 | 根本原因 | 修復方式 |
|---|---|---|
| 收藏卡片空白 | `abstractText` 序列化名稱不符 | 加 `@JsonProperty("abstract")` |
| AI 摘要重點顯示為 JSON 字串 | getter 回傳原始字串而非陣列 | 加 `@JsonIgnore` + 解析 getter |
| 圖書館沒有顯示任何文件 | `downloadedKeys` Set 已被移除但 Sidebar 仍引用 | 改用 `downloads` 陣列 |
| Admin 頁面空白 | Sidebar 引用已刪除的 `downloadedKeys.size` 導致靜默崩潰 | 改為 `downloads.length` |
| 切換期間沒有變化 | 快速選擇按鈕只更新 `pendingDays`，未觸發 fetch | 改為立即呼叫 `fetchPapers` |
| 哈佛商業評論沒有回應 | 資料庫沒有 HBR 文章 | Scheduler 啟動時 seed 6 篇示範文章 |
| Preview / Open cached 無效 | 未開啟 Modal、未使用 `window.open` | 新增 `PaperPreviewModal` 元件 |
| Remove / Delete 缺乏確認 | 直接刪除 | 新增可重用的 `ConfirmDialog` 元件 |

---

## 十四、部署相關

### 環境變數

```properties
# 後端
ANTHROPIC_API_KEY=sk-ant-...   # 或 GEMINI_API_KEY（免費替代）
JWT_SECRET=至少32字元的隨機字串
SPRING_DATASOURCE_URL=jdbc:mysql://...
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...

# 前端
NEXT_PUBLIC_API_URL=http://localhost:8080/api
```

### AI 摘要替代方案（免費）
- **Google AI Studio**：每天 1,500 次，不需信用卡（推薦）
- **Groq**：速度最快，使用 Llama 3.3 70B
- **Ollama**：完全本機，無限次數，完全免費

### 雲端部署選項
- **Render + Vercel**：最簡單，30 分鐘上線，閒置會 sleep
- **Oracle Cloud Always Free**：4核心 ARM、24GB RAM，永久免費，需要 Linux 基礎

---

## 十五、RWD 與 UI 規範

- 工具列採用 `flex-wrap` 單行排列，視窗縮小時自動換行
- 來源切換時更新：Sidebar 標題、主題清單、趨勢圖、Admin 主題設定
- 所有刪除操作必須經過確認對話框
- 確認框預設 focus 在「取消」按鈕，按 Escape 可關閉
- 配對排序為紫色，與其他排序視覺區分
- 已收藏文章以綠色邊框和背景標示
- 關鍵字晶片依優先順序以顏色區分（綠/藍/橙）
