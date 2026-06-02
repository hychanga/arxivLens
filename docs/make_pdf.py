# -*- coding: utf-8 -*-
"""Render the 3-page arxivLens intro deck as a landscape PDF (mirrors make_deck.py)."""
from reportlab.pdfgen import canvas
from reportlab.lib.colors import Color
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

pdfmetrics.registerFont(TTFont("JhengHei", r"C:\Windows\Fonts\msjh.ttc", subfontIndex=0))
pdfmetrics.registerFont(TTFont("JhengHei-Bold", r"C:\Windows\Fonts\msjhbd.ttc", subfontIndex=0))
pdfmetrics.registerFontFamily("JhengHei", normal="JhengHei", bold="JhengHei-Bold",
                              italic="JhengHei", boldItalic="JhengHei-Bold")

INCH = 72.0
PW, PH = 13.333 * INCH, 7.5 * INCH


def rgb(v):
    return Color(((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)

INK   = rgb(0x18181B); MUTED = rgb(0x71717A); ACCENT = rgb(0x2563EB)
WHITE = rgb(0xFFFFFF); LINE  = rgb(0xE4E4E7)
BOX_FE = rgb(0xEFF6FF); BOX_BE = rgb(0xECFDF5); BOX_DB = rgb(0xFEF3C7); BOX_GREY = rgb(0xF4F4F5)
BD_FE = rgb(0x93C5FD); BD_BE = rgb(0x6EE7B7); BD_DB = rgb(0xFCD34D)

c = canvas.Canvas(r"D:\greg\project\arxivlens\docs\arxivLens-系統介紹.pdf", pagesize=(PW, PH))


def box(x, y, w, h, fill, border, title=None, lines=None, tsize=10.5, lsize=9):
    """x,y in inches, top-left origin."""
    X, W, H = x * INCH, w * INCH, h * INCH
    Yll = PH - (y + h) * INCH
    c.setFillColor(fill); c.setStrokeColor(border); c.setLineWidth(1)
    c.roundRect(X, Yll, W, H, 6, fill=1, stroke=1)
    cx = X + W / 2
    nlines = (1 if title else 0) + len(lines or [])
    total = nlines * (tsize + 3)
    ty = PH - (y * INCH) - H / 2 + total / 2 - tsize
    if title:
        c.setFillColor(INK); c.setFont("JhengHei-Bold", tsize)
        c.drawCentredString(cx, ty, title); ty -= (tsize + 3)
    c.setFillColor(MUTED); c.setFont("JhengHei", lsize)
    for ln in (lines or []):
        c.drawCentredString(cx, ty, ln); ty -= (lsize + 3)


def rect(x, y, w, h, fill, border=None):
    Yll = PH - (y + h) * INCH
    c.setFillColor(fill)
    if border:
        c.setStrokeColor(border); c.setLineWidth(1)
        c.roundRect(x * INCH, Yll, w * INCH, h * INCH, 6, fill=1, stroke=1)
    else:
        c.rect(x * INCH, Yll, w * INCH, h * INCH, fill=1, stroke=0)


def glyph(x, y, w, h, ch, size, color=ACCENT):
    cx = (x + w / 2) * INCH
    cy = PH - (y + h / 2) * INCH - size * 0.35
    c.setFillColor(color); c.setFont("JhengHei-Bold", size)
    c.drawCentredString(cx, cy, ch)


def para(x, y, w, html, size=12, leading=15, color=INK, bold=False, align=TA_LEFT, space=3):
    st = ParagraphStyle("s", fontName="JhengHei-Bold" if bold else "JhengHei",
                        fontSize=size, leading=leading, textColor=color,
                        alignment=align, spaceAfter=space)
    p = Paragraph(html, st)
    aw = w * INCH
    _, ph = p.wrap(aw, PH)
    p.drawOn(c, x * INCH, PH - (y * INCH) - ph)
    return ph / INCH


def header(zh, en, n):
    rect(0.6, 0.55, 0.12, 0.62, ACCENT)
    para(0.85, 0.55, 11.0,
         f'<font name="JhengHei-Bold" size="26" color="#18181B">{zh}</font>'
         f'<font size="15" color="#71717A">  {en}</font>', size=26, leading=30)
    para(11.6, 0.62, 1.2,
         f'<font name="JhengHei-Bold" color="#71717A">{n}/3</font>', size=12, align=TA_CENTER)


def page_bg():
    c.setFillColor(WHITE); c.rect(0, 0, PW, PH, fill=1, stroke=0)


# ===== Slide 1 — Architecture =====
page_bg()
header("系統架構", "System Architecture", 1)
para(0.85, 1.35, 11.8, "arxivLens — arXiv / HBR 論文聚合閱讀器，含 AI 摘要、關鍵字評分與多語翻譯",
     size=13, color=MUTED)
row_y, bw, bh = 2.35, 2.55, 1.45
xs = [0.7, 3.65, 6.6, 9.55]
box(xs[0], row_y, bw, bh, BOX_GREY, LINE, "使用者瀏覽器", ["Browser", "桌機 / 行動裝置"])
box(xs[1], row_y, bw, bh, BOX_FE, BD_FE, "前端 Frontend", ["Vercel", "Next.js 16 · React 19", "TypeScript · Tailwind 4", "Zustand"])
box(xs[2], row_y, bw, bh, BOX_BE, BD_BE, "後端 Backend", ["Render (Docker)", "Spring Boot 4 · Java 25", "Spring Security · JWT"])
box(xs[3], row_y, bw, bh, BOX_DB, BD_DB, "資料庫 Database", ["TiDB Cloud Serverless", "(MySQL 相容)", "本地開發用 MySQL 8"])
for ax in (3.30, 6.25, 9.20):
    glyph(ax, row_y, 0.35, bh, "→", 20)
para(3.65, row_y + bh + 0.05, 5.9, "HTTPS · REST / JSON · JWT", size=9, color=MUTED, align=TA_CENTER)
glyph(xs[2] + bw / 2 - 0.2, row_y + bh - 0.05, 0.4, 0.55, "↓", 18)
ext_y = row_y + bh + 0.65
box(0.7, ext_y, 11.4, 1.35, WHITE, LINE, "外部整合 External integrations", [])
chips = [("arXiv Atom API", "論文同步"), ("HBR RSS", "文章來源"),
         ("Google Gemini 2.5 Flash", "摘要 / 翻譯"), ("Resend", "Email 通知"),
         ("GitHub Actions", "每 6 小時 cron")]
cw, gap, cx = 2.12, 0.12, 0.95
for name, sub in chips:
    box(cx, ext_y + 0.45, cw, 0.78, BOX_GREY, LINE, name, [sub], tsize=10, lsize=9)
    cx += cw + gap
para(0.7, 6.95, 11.8, "部署：Vercel（前端）· Render（後端）· TiDB Cloud（資料庫）— 全部免費方案",
     size=10.5, color=MUTED)
c.showPage()


# ===== Slide 2 — Features =====
page_bg()
header("系統特色", "Key Features", 2)
groups = [
    ("內容與閱讀", "Feed & Reading", [
        "Latest 聚合 arXiv + HBR，依主題 / 天數過濾",
        "關鍵字相關性評分，重點論文一眼可見",
        "交叉分類標示（cs.CV 論文掛 cs.AI 也會顯示 +cs.AI）",
        "Library 本地快取 PDF，內建檢視器（讀取時 loading 游標）"]),
    ("AI 與多語", "AI & i18n", [
        "Gemini 2.5 Flash：依需求生成 AI 摘要",
        "標題 / 摘要翻譯 zh-TW · zh-CN · ja · de",
        "每篇翻譯快取於 DB，整頁批次查詢（省連線）"]),
    ("趨勢分析", "Trends", [
        "12 個月主題堆疊長條圖",
        "採 arXiv 官方總量（opensearch totalResults）"]),
    ("帳號與安全", "Auth & Security", [
        "Email / 密碼登入 + JWT",
        "兩階段驗證 2FA（TOTP）",
        "Sign in with Google / Apple、密碼重設"]),
    ("維運自動化", "Ops", [
        "Admin：來源 / 主題 / 設定、深度 resync、backfill",
        "每 6 小時自動同步 + 郵件通知",
        "外部 cron 觸發（克服 Render 免費方案休眠）"]),
]
layout = {0: 0, 1: 0, 2: 0, 3: 1, 4: 1}
col_x = [0.7, 6.95]; col_w = 5.9; col_y = [1.55, 1.55]
for gi in range(5):
    col = layout[gi]; zh, en, items = groups[gi]; y = col_y[col]
    para(col_x[col], y, col_w,
         f'<font name="JhengHei-Bold" size="15" color="#2563EB">▍{zh}</font>'
         f'<font size="11" color="#71717A">  {en}</font>', size=15, leading=19)
    y += 0.42
    bullets = "<br/>".join(
        f'<font color="#2563EB">•</font>  {it}' for it in items)
    used = para(col_x[col], y, col_w, bullets, size=12, leading=17, color=INK, space=0)
    col_y[col] = y + used + 0.28
c.showPage()


# ===== Slide 3 — Data Flow =====
page_bg()
header("資料流", "Data Flow", 3)
para(0.7, 1.45, 11.8,
     '<font name="JhengHei-Bold" size="15" color="#18181B">①  同步流程 Sync — 每 6 小時自動</font>',
     size=15, leading=19)
ay, abh = 1.98, 1.15
sync = [("GitHub Actions", "cron 0 */6", BOX_GREY, LINE),
        ("/api/cron/arxiv-sync", "token 驗證", BOX_BE, BD_BE),
        ("ArxivSyncService", "分頁・退避重試", BOX_BE, BD_BE),
        ("arXiv Atom API", "lastUpdatedDate", BOX_GREY, LINE),
        ("逐頁交易 upsert", "categories 回填", BOX_BE, BD_BE),
        ("TiDB", "papers", BOX_DB, BD_DB)]
n = len(sync); abw = (11.4 - 0.30 * (n - 1)) / n; ax = 0.7
for i, (t1, t2, f, b) in enumerate(sync):
    box(ax, ay, abw, abh, f, b, t1, [t2], tsize=10, lsize=9)
    if i < n - 1:
        glyph(ax + abw - 0.02, ay, 0.34, abh, "→", 16)
    ax += abw + 0.30
glyph(0.7 + abw / 2 - 0.2, ay + abh - 0.05, 0.4, 0.5, "↓", 15)
box(0.7, ay + abh + 0.45, 4.6, 0.7, BOX_GREY, LINE, "Resend 寄出摘要信",
    ["新增 / 跳過 / 錯誤統計 → hychanga@gmail.com"], tsize=10, lsize=9)

by = 4.78
para(0.7, by, 11.8,
     '<font name="JhengHei-Bold" size="15" color="#18181B">②  閱讀流程 Read — 即時依需求</font>',
     size=15, leading=19)
ry = by + 0.5
read = [("使用者 / Browser", "Next.js Feed", BOX_FE, BD_FE),
        ("GET /api/papers", "主題 / 天數 / 分頁", BOX_BE, BD_BE),
        ("TiDB", "查詢已同步論文", BOX_DB, BD_DB),
        ("論文卡片", "評分 · 翻譯 · 收藏", BOX_FE, BD_FE)]
n2 = len(read); rbw = (11.4 - 0.30 * (n2 - 1)) / n2; rx = 0.7
for i, (t1, t2, f, b) in enumerate(read):
    box(rx, ry, rbw, 1.0, f, b, t1, [t2], tsize=10.5, lsize=9)
    if i < n2 - 1:
        glyph(rx + rbw - 0.02, ry, 0.34, 1.0, "→", 16)
    rx += rbw + 0.30
para(0.7, ry + 1.12, 11.8,
     "翻譯 / 摘要：批次 GET /api/papers/translations → 命中 DB 快取，否則呼叫 Gemini 生成後寫回快取",
     size=10.5, color=MUTED)
c.showPage()

c.save()
print("saved PDF")
