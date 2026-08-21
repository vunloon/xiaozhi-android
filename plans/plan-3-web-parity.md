# Plan 3 — Bring the Android app to xiaozhi-web parity

Goal: give `xiaozhi-android` the look, listening modes, themes, settings menu,
and in-app MCP tools of the browser client at `../xiaozhi-web`, per user
decisions (WebView music embed, defer camera, both MCP channels, full restyle).

## Decisions (user-confirmed)

- Music playback: **WebView youtube-nocookie embed** (mirrors web `MusicPlayer.tsx`).
- Camera / `take_photo` tool: **deferred**.
- Reverse-WSS MCP server channel (`wss://api.xiaozhi.me/mcp/?token=<JWT>`): **included**.
- UI restyle: **full** — web layout.

## The web features to port

| Web feature | Source files | Android gap |
|---|---|---|
| 3 listening modes (manual/auto/realtime) | `ui/Controls.tsx`, `core/protocol.ts` | PTT only in `state/ChatStateMachine.kt` |
| Themes (dark/beige/blue/green/pink) + bg images | `ui/SettingsPanel.tsx`, `index.css`, `public/background/*.jpeg` | Hardcoded pastel palette in `ui/theme/Theme.kt` |
| Settings menu | `ui/SettingsPanel.tsx`, `core/config.ts` | No settings screen; URLs/token hardcoded in `WebSocketProtocol` |
| Web look (status-dot topbar, emotion orb, pill mode switch, minimal bubbles, stage) | `ui/App.tsx`, `ui/StatusOrb.tsx`, `index.css` | Bear-avatar bubbles, drawer, FAB |
| In-app MCP tools (news/KLSE/watchlist/music) over voice + reverse-WSS | `tools/*`, `core/mcp.ts`, `core/mcpWss.ts` | `handleMcpRequest` returns empty catalog; no tools |

Protocol groundwork already exists: `ListenMode` (`manual/auto/realtime`) in
`ProtocolMessage.kt:67`; `listen start` already carries `mode`.

## Phases

### Phase 1 — Settings store + settings UI
- New `core/settings/SettingsStore.kt` (DataStore): `{otaUrl, wsUrl, token,
  visionBase, mcpWssUrl, theme}`; defaults mirror `config.ts`
  (`otaUrl=https://api.tenclass.net/xiaozhi/ota/`, `wsUrl=WebSocketProtocol.DEFAULT_URL`,
  `token=test-token`, `visionBase=''`, `mcpWssUrl=wss://api.xiaozhi.me/mcp/?token=`, `theme=dark`).
- Refactor `WebSocketProtocol` / `OtaClient` to read settings overrides.
- New `ui/settings/SettingsScreen.kt` + VM (mirrors `SettingsPanel.tsx`):
  URL/token fields, MCP-WSS toggle, theme swatches, identity block
  (MAC/serial/client/status, copy JSON, reset, import JSON).
- `EfuseStore`: `exportJson()` / `importJson(raw)` (web `{mac,clientId,serialNumber,hmacKey}` format).
- New nav route `settings`.

### Phase 2 — Themes
- `Theme.kt`: `ThemeId` (DARK/BEIGE/BLUE/GREEN/PINK) + `XiaozhiPalette` mapped
  from `index.css` vars (bg, bg2, card, line, text, dim, accent, accent2,
  danger, ok, warn, panel, userBubbleBg, userBubbleLine).
- Copy `public/background/background_{beige,blue,green,pink}.jpeg` into
  `res/drawable-nodpi/`; dark = flat color (web behavior).
- CompositionLocal palette + background swap driven by `SettingsStore.theme`.
- Replace hardcoded colors in `ChatScreen`/`ActivationScreen` with palette.

### Phase 3 — Three microphone modes
- `ChatStateMachine`: `listeningMode` + `sessionActive`; `startListening(mode)`
  sends `listen start {mode}`; `endConversation()`; `abort()`; on TTS-stop
  re-issue `listen start` when `sessionActive`; realtime keeps capture during
  speaking (`shouldCapture()`). Mirror `protocol.ts` exactly.
- `ChatViewModel`: `setMode`/`endConversation`/`abort`/emotion flows.
- `AudioCapture`: expose mic-level RMS flow for the orb animation.

### Phase 4 — UI restyle
- Rebuild `ChatScreen.kt`: topbar (status dot + label + ⏻ connect/disconnect +
  ⚙ settings), emotion `StatusOrb`, web-style bubbles (no avatars/timestamps),
  bottom controls (3-mode pill switch + round mic button + abort + text row).
- Tool-activity drawer moves into Settings (top bar matches web).
- Activation screen theme-adapted (keep existing flow).

### Phase 5 — In-app tools (both channels)
- New `mcp/`: `Tool`, `ToolRegistry`, `McpDispatcher` (initialize/ping/tools/list/tools/call).
- Port `src/tools/*` with OkHttp (no CORS on Android) + **Jsoup** (new dep
  `org.jsoup:jsoup:1.17.2`); watchlist in DataStore:
  - news: `get_oriental_daily_news`, `get_sinchew_news`, `get_enanyang_news`, `get_news_article`
  - klse: `get_klse_stock_data`, `search_klse_stock_code`
  - watchlist: `add_to_watchlist`, `get_watchlist`, `remove_from_watchlist`
  - music: `search_music`, `play_music`/`stop_music` → `MusicPlayer` + WebView embed card
- Voice channel: fill `handleMcpRequest` with registry.
- New `mcp/McpWssClient.kt`: OkHttp WSS to `wss://api.xiaozhi.me/mcp/?token=<JWT>`,
  shared dispatcher, capped-backoff reconnect, state surfaced in Settings.
- `take_photo` deferred.

### Phase 6 — Verification
- `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`.
- Emulator smoke via `scripts/emulator.sh` (themes, modes, settings, tool handshake).
- Update README/plans.

## Risks
- Reverse-WSS JWT collision with the Python `xiaozhi-wss` host (same
  endpointId kicks the other off) — rotate or issue a 2nd JWT.
- Upstreams (klsescreener Cloudflare, YTM innertube) may block — mirror web's
  browser headers + graceful error strings.
- Music embed: some tracks are embed-disabled; WebView "open in YouTube" fallback.

## Status — DONE (2026-08-20)

All phases implemented and verified on the emulator (`emulator-5554`):

- **Build/tests**: `./gradlew :app:assembleDebug` clean; `:app:testDebugUnitTest` pass.
- **Chat screen**: status-dot top bar (Online/⏻/⚙), 3-mode pill switch
  (Manual/Auto/Realtime), round talk button, text row — rendered.
- **Settings screen**: URL/token/vision/MCP-WSS fields, MCP-WSS connect toggle
  (real WS attempt → 401 with the test token → reconnect backoff → Off on
  disconnect), 5 theme swatches, identity block (copy/reset/import), tool
  activity log.
- **Themes**: switching + persistence verified via DataStore (`theme` dark→beige→dark).
- **In-app tools live on voice channel**: cloud `initialize` handshake answered;
  cloud `tools/list` now returns the full local catalog (news ×4, klse ×2,
  watchlist ×3, music ×3). `tools/call` routes through the same `McpDispatcher`.
- **Reverse-WSS**: `McpWssClient` connects/disconnects with capped backoff; state
  surfaces in Settings.

### Caveats from testing
- The emulator device identity was **reset during smoke testing** (the Settings
  "Reset identity" button was tapped accidentally while verifying the UI). The
  device is now un-linked; next launch shows the Device Activation screen and
  requires re-linking this device on xiaozhi.me (normal `scripts/emulator.sh reset`
  workflow). Theme was left at the default `dark`.
- Tools/news/KLSE/watchlist/music upstream calls were not exercised end-to-end on
  the emulator (the cloud only issued `tools/list`, not a real `tools/call`); the
  parse/fetch code mirrors the web implementation (Jsoup instead of DOMParser).