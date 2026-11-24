# Technical Architecture

## 1. Strict Local-First Design
All agent orchestration, Room SQLite persistence, workspace files, terminal execution, browser automation, and WorkManager scheduling run locally on the Android device with **no self-hosted backend**.

## 2. Two-Controller Separation
- **Controller A (Internal Virtual Environment):** Controls `virtual_env/` files, `/system/bin/sh`, PyLite/native Python, Android V8 JS, local HTTP server (`127.0.0.1:8080`), and the integrated WebView browser.
- **Controller B (Android Device Automation):** Controls `PersonalAiAccessibilityService` only after the user explicitly enables it in Android Accessibility Settings.

## 3. Honest Platform Limitations
- **PRoot / Full VM:** Normal unrooted Android devices do not allow arbitrary KVM virtualization inside standard Play-compliant APKs, and PRoot is a userspace path translation layer, not a full VM. This app provides a real sandboxed POSIX shell + on-device Python & V8 JS runtimes.
- **Playwright:** Desktop Playwright Chromium binaries are unsupported inside stock Android APKs; `BrowserController` implements a compatible WebView DOM automation adapter.
- **RAM Budgets:** Android does not allow apps to lock physical RAM permanently; selected budgets enforce concurrency caps and execution limits.
