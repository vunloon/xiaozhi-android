# xiaozhi-android — Implementation Plans

Build an Android version of xiaozhi AI chat that reuses the existing xiaozhi cloud
backend for LLM and TTS (no self-hosted LLM). Tools are sampled from
[`../xiaozhi-wss`](../../xiaozhi-wss).

## Plans

| # | Plan | Goal | Effort |
|---|------|------|--------|
| 1 | [Voice chat client](./plan-1-voice-client.md) | Full xiaozhi voice client (mic, Opus, TTS, activation v2). Tools come from a **remote** MCP server via WSS (e.g. your existing `xiaozhi-wss` Python host). | ~9–10 days |
| 2 | [Port WSS tools into the app](./plan-2-mcp-tools.md) | The same Android app also acts as an MCP tool server (outbound WSS to `api.xiaozhi.me/mcp/`), serving news/stocks/music natively. Eliminates the Python host. | ~6–7 days |

**Total: ~15–17 working days** for one Android-experienced developer.

## Sequencing

Plan 2 is **additive** to Plan 1 — it carries over nothing mandatory from Plan 1
but is designed to live in the same app. Recommended order:

1. Ship Plan 1 → working voice chat (tools via your remote `xiaozhi-wss`).
2. Then Plan 2 → app becomes self-sufficient for tools; retire the Python host.

## Two backends at a glance

| | Plan 1 device session | Plan 2 MCP broker |
|---|---|---|
| Endpoint | `wss://api.tenclass.net/xiaozhi/v1/` | `wss://api.xiaozhi.me/mcp/?token=<JWT>` |
| Auth | `Bearer test-token` header + `Device-Id` (MAC) + `Client-Id` (UUID) + activation v2 | JWT in `?token=` query param (carries `userId/agentId/endpointId`) |
| Carries | Audio (Opus binary), listen/abort/tts/stt/llm/iot JSON, optional in-session `mcp` envelope | MCP JSON-RPC 2.0 only (`initialize` / `tools/list` / `tools/call` / `ping`) |
| Keepalive | WSS ping frame | App must answer 60 s `ping` JSON-RPC or broker drops the socket |

## Open questions to confirm before coding

1. **Music tool stream resolver** — `yt-dlp` (used by `xiaozhi-wss`) has no Android
   port. Recommend **NewPipe Extractor** (`com.github.TeamNewPipe:NewPipeExtractor`).
   Fallback: drop music from Plan 2 MVP.
2. **Wake word** — recommend **push-to-talk only** for Plan 1 MVP; add
   `sherpa-onnx` (Android AAR) post-ship if hands-free is needed.
3. **Custom server support** — both plans assume the official xiaozhi backend.
   If you want to support 3rd-party servers, the output sample rate drops to
   16 kHz and frame duration may rise to 60 ms (see `py-xiaozhi/src/constants/constants.py:49-87`).
4. **Plan 2 vs. your running Python `xiaozhi-wss`** — they register the same
   `endpointId` if they share a JWT. Either: (a) Plan 2 is meant to *replace*
   the Python host, or (b) issue a **second MCP endpoint/JWT** from the Xiaozhi
   console for the Android app so both can coexist.

## Reference codebases used

- [`/home/vunloon/dev/py-xiaozhi`](../../py-xiaozhi) — reference Python voice client (device session protocol).
- [`/home/vunloon/dev/xiaozhi-wss`](../../xiaozhi-wss) — reference Python MCP tool server (reverse-WSS pattern, JWT auth).

> ⚠️ **Security note**: `xiaozhi-wss/wss.log:1` contains a **live JWT** (valid
> until ~Apr 2027). If you reuse or fork that repo, rotate the token from the
> Xiaozhi console and delete `wss.log`. Do not commit logs containing tokens.
