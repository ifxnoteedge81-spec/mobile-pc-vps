# Security & Privacy Model

## Key Storage
- User-entered Gemini API keys, OpenRouter API keys, and optional YouTube OAuth tokens are encrypted locally via **Android Keystore AES-256-GCM** (`SecureKeyStore.kt`).
- No API keys are hardcoded or committed to GitHub.

## 3-Tier Permission Policy
1. **Level 1 (Safe):** Workspace reads/writes, safe shell/Python/JS scripts, public web browsing/DOM extraction.
2. **Level 2 (Confirmation Required):** Deleting files, filling forms, downloading files, Accessibility Virtual Hand gestures, scheduling automations, and destructive/network shell commands.
3. **Level 3 (Restricted — Blocked):** Bypassing login/OTP/CAPTCHA/biometrics, reading password fields (`isPassword`), inspecting banking/authenticator apps, or destructive OS commands (`rm -rf /`).
