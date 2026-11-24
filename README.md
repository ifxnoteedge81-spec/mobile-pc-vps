# PERSONAL AI COMPUTER

A **local-first autonomous mobile AI agent operating environment** for Android built with Kotlin, Jetpack Compose, Material 3, Room SQLite, DataStore, WorkManager, Android Keystore, Chromium WebView, and a sandboxed Linux-compatible userspace.

## Core Pillars
1. **AI Brain + Autonomous Agent (`ai/`, `agent/`)**:
   - Direct HTTPS gateway to **Google Gemini API** and **OpenRouter API** (with SSE streaming, tool calling, live free-model discovery, and automatic fallback).
   - **Autonomous Agent Engine** inspired by OpenClaw's tool-calling cycle: Planning -> Tool Selection -> Execution -> Verification -> Recovery -> Level 2 Confirmation -> Final Report (Bangla & English).
2. **Local Virtual Linux Environment (`runtime/`, `terminal/`, `resources/`)**:
   - Sandboxed FHS workspace (`virtual_env/home`, `workspace`, `projects`, `scripts`, `downloads`, `tmp`, `memory`).
   - POSIX `/system/bin/sh` execution, native `python3` detection + on-device **PyLite** Python 3 interpreter, Android V8 JavaScript/TypeScript engine, and a local `127.0.0.1:8080` HTTP dev server.
   - Real hardware telemetry & resource budgets (`512 MB` – `8 GB` / Custom) with automatic concurrency throttling.
3. **Browser + Virtual Hands (`browser/`, `accessibility/`)**:
   - Multi-tab **Chromium WebView** browser with DOM text/links/forms extraction, element click/fill, snapshots, bookmarks, and downloads.
   - **Android Accessibility Service** (`AI Computer Virtual Hands`) for explicit user-enabled UI inspection and gestures (`tap`, `doubleClick`, `longPress`, `swipe`, `setText`, `BACK/HOME`), strictly blocking password, OTP, banking, and biometric screens.

## Documentation
- [Architecture & Technical Limitations](docs/ARCHITECTURE.md)
- [API Key & Provider Setup](docs/API_SETUP.md)
- [Security & 3-Tier Permission Policy](docs/SECURITY.md)
- [Build & GitHub Actions](docs/BUILD.md)
- [Netlify Static APK Deployment](docs/NETLIFY_DEPLOYMENT.md)
