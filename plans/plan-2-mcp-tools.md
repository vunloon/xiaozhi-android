# Plan 2 — Port WSS Tools Into the App (MCP tool server)

The same Android app built in [Plan 1](./plan-1-voice-client.md) **also** acts
as an MCP tool server, eliminating the need for the separate Python
[`xiaozhi-wss`](../../xiaozhi-wss) host. Adds a **second outbound WSS
connection** to `wss://api.xiaozhi.me/mcp/?token=<JWT>` and serves the ported
tools (news / stocks / music) itself.

- **Endpoint**: `wss://api.xiaozhi.me/mcp/?token=<JWT>`
- **Auth**: JWT in `?token=` (carries `userId/agentId/endpointId/purpose:"mcp-endpoint"`)
- **Protocol**: MCP over JSON-RPC 2.0 (`initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping`)
- **Effort**: ~6–7 working days

> Plan 2 is **additive** — it can run standalone (without Plan 1's voice
> client), but it is designed to live in the same app. After Plan 2 ships you
> can **shut down your Python `xiaozhi-wss`** entirely.

---

## Architecture (additions to the Plan 1 app)

```
┌──── Android app (Plan 1 + Plan 2 additions) ────┐
│                                                  │
│  [Plan 1 voice client — unchanged]               │
│                                                  │
│  + NEW: McpServer (in-process, JSON-RPC 2.0)     │
│  + NEW: McpWssClient (outbound to                │
│  │       api.xiaozhi.me/mcp/?token=<JWT>)        │
│  │       - JWT from JwtStore (EncryptedPrefs)    │
│  │       - 60 s ping keepalive (CRITICAL)        │
│  │                                               │
│  + NEW: ToolRegistry + tools/                    │
│       ├── news/    (4 fns, port from server.py)  │
│       ├── stock/   (2 fns + watchlist 3 fns)     │
│       └── music/   (3 fns + Media3 ExoPlayer)    │
│                                                  │
│  + NEW UI: JWT setup, tool catalog,              │
│            Now-Playing card, Watchlist screen    │
└──────────────────────────────────────────────────┘
        │                              │
        │ device session               │ MCP broker (NEW)
        ▼                              ▼
  api.tenclass.net/v1/        api.xiaozhi.me/mcp/
```

Two simultaneous outbound WSS connections from one app. They are fully
independent — voice works whether or not the MCP server is connected, and
vice versa.

## Key difference from Python `xiaozhi-wss`

The Python repo has a two-process bridge: `wss_client.py` (WSS ↔ newline-stdio)
and `server.py` (FastMCP over stdio). On Android this collapses — the MCP
server **is** the app, in-process. There is no subprocess, no newline framing,
no `readline()`. Frames flow directly from WSS to the JSON-RPC dispatcher.

## MCP protocol surface (mirror the `wss.log` handshake exactly)

| Direction | Message | Notes |
|---|---|---|
| cloud → app | `initialize` (`id:0`, `protocolVersion:"2024-11-05"`, `clientInfo:{name:"xz-mcp-broker", version:"0.0.1"}`, capabilities `sampling`+`roots`) | Reply with our `protocolVersion:"2024-11-05"`, `capabilities:{tools:{listChanged:false}}`, `serverInfo:{name:"xiaozhi-android", version:"1.0.0"}` |
| cloud → app | `notifications/initialized` | No reply expected |
| cloud → app | `tools/list` (`id:1`) | Return tool catalog with `inputSchema` per tool. Paginate at 8 KB using `cursor` / `nextCursor` (per `py-xiaozhi/src/mcp/mcp_server.py:433-468`) |
| cloud → app | `tools/call` (`id:N`, `params:{name, arguments}`) | Dispatch via registry → `{result:{content:[{type:"text", text:...}], isError:false}}` |
| cloud → app | `ping` (every 60 s) | Reply `{jsonrpc:"2.0", id:N, result:{}}` — broker **drops the socket** on missed ping |

Sample `tools/call` request:
```json
{"id":7, "jsonrpc":"2.0", "method":"tools/call",
 "params":{"name":"get_klse_stock_data", "arguments":{"code":"1155"}}}
```
Sample reply:
```json
{"jsonrpc":"2.0", "id":7,
 "result":{"content":[{"type":"text","text":"{\"code\":\"1155\",\"name\":\"MAYBANK\",\"price\":\"8.55\",...}"}],
           "isError":false}}
```

## Tools to port (from [`xiaozhi-wss/server.py`](../../xiaozhi-wss/server.py))

| # | Tool | Source | What it does |
|---|------|--------|--------------|
| 1 | `get_oriental_daily_news(category)` | `server.py:89-106` | Headlines from orientaldaily.com.my |
| 2 | `get_sinchew_news(category)` | `server.py:108-124` | Headlines from sinchew.com.my |
| 3 | `get_enanyang_news(category)` | `server.py:126-142` | Headlines from enanyang.my |
| 4 | `get_news_article(url)` | `server.py:59-87` | Full body text (≤3000 chars) |
| 5 | `search_klse_stock_code(query)` | `server.py:218-242` | Company name → stock code (top 10) |
| 6 | `get_klse_stock_data(code)` | `server.py:144-216` | Quote + fundamentals + dividends |
| 7 | `get_watchlist()` | `server.py:373-441` | Live prices + P/L for holdings (≤8 parallel) |
| 8 | `add_to_watchlist(code, shares?, buy_price?)` | `server.py:320-370` | Upsert into watchlist |
| 9 | `remove_from_watchlist(code)` | `server.py:444-458` | Remove from watchlist |
| 10 | `search_music(query, artist?, gender?, limit?)` | `server.py:516-537` | YouTube Music search |
| 11 | `play_music(video_id)` | `server.py:680-724` | Resolve stream URL → play in-app (ExoPlayer) |
| 12 | `stop_music()` | `server.py:727-734` | Stop playback |

Two-step list→detail pattern is intentional for news (keeps LLM context small).
All tools return a JSON string or a `[TextContent]` list, matching the Python
behaviour.

## Module layout (additions)

```
app/src/main/java/com/example/xiaozhi/
├── [Plan 1 modules — unchanged]
├── core/
│   └── transport/
│       ├── McpWssClient.kt             // OkHttp WSS to api.xiaozhi.me/mcp/?token=
│       │                               //   60 s ping responder, auto-reconnect
│       ├── McpFrame.kt                 // JSON-RPC 2.0 envelope (@Serializable)
│       └── Keepalive.kt
├── mcp/
│   ├── McpServer.kt                    // initialize / tools-list / tools-call dispatcher
│   ├── ToolRegistry.kt                 // register, schema-from-Kotlinx-serialization
│   ├── Tool.kt                         // interface: name, description, inputSchema, suspend call(args)
│   ├── Content.kt                      // TextContent, isError wrapper
│   └── identity/JwtStore.kt            // EncryptedSharedPreferences (+ exp decode)
├── tools/
│   ├── news/
│   │   ├── NewsTool.kt                 // 4 fns
│   │   └── NewsApi.kt                  // Ktor client + Jsoup
│   ├── stock/
│   │   ├── StockTool.kt                // 2 fns
│   │   ├── WatchlistTool.kt            // 3 fns
│   │   ├── StockApi.kt                 // klsescreener.com
│   │   └── WatchlistRepo.kt            // Room (replaces watchlist.json)
│   └── music/
│       ├── MusicTool.kt                // 3 fns
│       ├── MusicRepo.kt                // ytmusic search + NewPipe stream resolve
│       └── MusicService.kt             // Media3 MediaSessionService + ExoPlayer
├── data/db/WatchlistDao.kt             // Room
└── ui/
    ├── jwt/JwtSetupScreen.kt + VM      // paste MCP endpoint URL/token, expiry countdown
    ├── dashboard/DashboardScreen.kt    // status, call log, tool catalog
    ├── watchlist/WatchlistScreen.kt    // local management UI
    └── components/NowPlayingCard.kt
```

## Phased delivery

### Phase A — Transport + MCP core *(~2 days)*
- `McpWssClient`: OkHttp WebSocket to `wss://api.xiaozhi.me/mcp/?token=<JWT>`,
  auto-reconnect with backoff, foreground service (shared with Plan 1 or its own).
- `JwtStore`: paste-JWT UI, encrypted persistence, decode `exp` claim and warn
  7 days before expiry.
- `McpServer`: handshake (`initialize` → `notifications/initialized` →
  `tools/list`), 60 s ping responder, `tools/call` dispatcher.
- `ToolRegistry`: register tools, build `inputSchema` from `@Serializable`
  args class. Cursor pagination from day 1 (8 KB threshold).
**Verify**: connect to broker, log shows the full handshake + 60 s pings
(mirror `xiaozhi-wss/wss.log`).

### Phase B — News tools *(~1 day)*
- Port `server.py:59-142` using **Ktor client + Jsoup**:
  `get_oriental_daily_news`, `get_sinchew_news`, `get_enanyang_news`,
  `get_news_article`.
- Same 2-step list→detail pattern; ≤15 headlines; 3000-char article cap.
- `category` enum: `local`, `international`, `finance`, `entertainment`, `other`.
**Verify**: ask the agent "give me Sinchew finance headlines" → tool fires,
JSON returned, agent TTS-reads the result.

### Phase C — Stock tools + watchlist *(~1.5 days)*
- Port `server.py:144-458`:
  `search_klse_stock_code`, `get_klse_stock_data`, `get_watchlist`,
  `add_to_watchlist`, `remove_from_watchlist`.
- Watchlist backed by **Room** (replaces `watchlist.json`). Concurrency-safe
  via Coroutines mutex (mirrors Python `_watchlist_lock`).
- Parallel fan-out ≤8 for batch quotes using `async { }` + `awaitAll`.
- Optional local `WatchlistScreen` for management without voice.
**Verify**: "what's Maybank's price" / "add 1155 to my watchlist" / "show my
watchlist" via voice.

### Phase D — Music tools *(~2 days — open issue below)*
- `search_music`: YouTube Music search.
- `play_music`: resolve stream URL → **Media3 ExoPlayer** (in-app, **not**
  subprocess spawn like Python's `mpv`/`vlc`/`ffplay`).
- `stop_music`: stop MediaSession.
- `NowPlayingCard` in dashboard; media notification with play/pause.
**Verify**: "play Bohemian Rhapsody" → in-app playback starts; agent confirms
via TTS.

### Phase E — Polish *(~0.5 day)*
- Toggle to enable/disable MCP server (battery-friendly; default off unless
  charging).
- zh-CN strings.
- Release build.

## Open issue — `yt-dlp` has no Android equivalent

`xiaozhi-wss/server.py:680-724` resolves a YouTube Music playable stream URL at
runtime via `yt-dlp` (Python-only, ships its own extractors). Options:

1. **NewPipe Extractor** (`com.github.TeamNewPipe:NewPipeExtractor`) — pure-Java
   YouTube stream resolver, works on Android. **Recommended.** Active
   maintenance but occasionally lags YT changes.
2. Run a small `yt-dlp`-compatible resolver service on a server — but you said
   no backends.
3. Drop music from Plan 2 MVP; add later.

## Decision to make before Plan 2 starts

After Plan 2 ships, your app will be **both** a voice device (to
`tenclass.net/v1/`) **and** an MCP tool server (to `xiaozhi.me/mcp/`). At that
point you could **shut down your Python `xiaozhi-wss`** entirely. Confirm:

- **(a)** Plan 2 is meant to *replace* the Python host — same JWT/endpointId,
  the Python server stops running. Or
- **(b)** Both run in parallel — issue a **second MCP endpoint/JWT** from the
  Xiaozhi console so they don't collide on `endpointId`.

If you do nothing, the second device to connect with the same JWT typically
kicks the first off — so this must be decided before testing.

## Risks

| Risk | Mitigation |
|---|---|
| Android kills WSS socket in background | Foreground service with persistent notification; `WorkManager` watchdog to restart |
| JWT expires (~1 yr window per `wss.log` token) | Decode `exp` claim on startup; warn user 7 days before; UI shows countdown |
| `xiaozhi-wss/wss.log` contains a leaked live JWT (valid until Apr 2027) | If reused, rotate from Xiaozhi console first; never ship in the APK |
| Cloudflare anti-bot on `klsescreener.com` / `orientaldaily.com.my` | Mirror Python's headers; cache last-known-good; if persistent, persist a cookie jar |
| MCP catalog > 8 KB | Cursor pagination from day 1 (we won't hit it with 12 tools, but cheap insurance) |
| `yt-dlp` unmaintained on Android | NewPipe Extractor; if blocked, drop music |
| Two concurrent WSS connections drain battery | User-toggleable MCP server; default off unless charging |

## Dependencies (Python → Android)

| xiaozhi-wss lib | Android replacement |
|---|---|
| `mcp` (FastMCP) | in-process `McpServer` (custom, ~200 LOC) |
| `websockets` | `okhttp3.WebSocket` |
| `httpx` | `Ktor client` |
| `beautifulsoup4` + `lxml` | `Jsoup` |
| `ytmusicapi` | ytmusic REST mirror or `NewPipe Extractor` for search |
| `yt-dlp` | **NewPipe Extractor** (recommended) |
| `watchlist.json` | `Room` database |
| `mpv`/`vlc`/`ffplay` (local player subprocess) | `Media3 ExoPlayer` (in-app) |
