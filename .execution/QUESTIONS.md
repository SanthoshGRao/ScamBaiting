# Clarification Questions — NotificationListenerService Integration

> **Task:** Implement NotificationListenerService + detection pipeline integration
> **Date:** 2026-04-13
> **Status:** Awaiting Answers

---

## Q1. Target Apps — Which apps to monitor?

Which messaging/communication apps should trigger scam detection?

* **Option A:** WhatsApp, Telegram, SMS only (minimal, high-value)
* **Option B:** WhatsApp, Telegram, SMS, Email (Gmail/Outlook), Instagram DMs
* **Option C:** ALL notification-producing apps (catch everything)

Should users be able to add/remove apps in settings?

> **Your Answer:**

🔥 Option B — WhatsApp, Telegram, SMS, Email, Instagram DMs

Why:
Covers real scam channels
Not too noisy like “all apps”
Still practical

👉 ALSO:

Users SHOULD be able to enable/disable apps in settings

## Q2. Notification Alert — What action buttons?

When a scam is detected, the alert notification should contain which buttons?

* **Option A:** "Dismiss" + "View Details" (minimal)
* **Option B:** "Dismiss" + "View Details" + "Block Sender" (moderate)
* **Option C:** "Dismiss" + "View Details" + "Block" + "Bait" (full scam-baiting)

> **Your Answer:**
🔥 Option C — Full system (BEST for your project)

Dismiss + View Details + Block + Bait
Why:
This is your USP (scam-baiting system)
Makes project stand out
Enables future StrategyAgent

## Q3. Detection Trigger — Every notification or filtered?

Should detection run on EVERY incoming notification or only "new message" types?

* **Option A:** Every notification (catches all, but more expensive)
* **Option B:** Only actual message content (filter out media-only, call, group meta)
* **Option C:** Smart filter — skip known-safe senders, skip groups, only new messages

> **Your Answer:**

🔥 Option C — Smart filter (IMPORTANT)

Rules:
✅ Only message content notifications
❌ Skip:
media-only
call notifications
system alerts
❌ Skip group messages (Phase 1)
Why:
reduces noise
saves battery
improves accuracy

## Q4. Alert Priority — Lockscreen behavior

Should scam alert notifications appear on lockscreen?

* **Option A:** Yes, always visible on lockscreen (high priority, heads-up)
* **Option B:** Only for HIGH risk scams, silent for LOW/MEDIUM
* **Option C:** Never on lockscreen (privacy-first)

> **Your Answer:**

🔥 Option B — Smart priority

HIGH risk → heads-up + lockscreen
MEDIUM → normal notification
LOW → silent
Why:
balances privacy + safety

## Q5. Service Architecture — Foreground service style

NotificationListenerService doesn't require a foreground notification by default (it's system-bound). However, background work (API calls) may need one. How to handle?

* **Option A:** No foreground service — rely on NotificationListenerService's special exemption
* **Option B:** Show persistent "ScamShield Active" notification (like antivirus apps)
* **Option C:** Only show foreground notification during active API processing, then remove

> **Your Answer:**

✅ Option A — No persistent foreground service

Why:
NotificationListenerService already has special privileges
avoids annoying persistent notification

👉 BUT:

ensure API calls are async + lightweight

## Q6. Duplicate Notification Handling

Same message may generate multiple notifications (e.g., WhatsApp updates notification on each new message in a conversation).

* **Option A:** Deduplicate by sender+text hash (skip if seen in last 5 min)
* **Option B:** Deduplicate by notification key (Android's built-in dedup)
* **Option C:** Process all (let detection cache handle dedup)

> **Your Answer:**

🔥 Option A — Deduplicate using sender + text hash

Window:
5 minutes
Why:
WhatsApp updates notifications frequently
prevents duplicate processing

👉 You already have SHA-256 cache → perfect fit

## Q7. Privacy — Should the service log any notification content?

* **Option A:** No logging of message text ever (pure privacy)
* **Option B:** Log only metadata (app, sender, timestamp) — no message text
* **Option C:** Structured log with hash of text (for debugging) + metadata

> **Your Answer:**

🔥 Option C — Metadata + HASH only

Log:
app
sender
timestamp
hash(message)

❌ NEVER log raw text

Why:
debug-friendly
privacy-safe

## Instructions

Answer each question. I'll proceed with risk analysis, alternatives, and implementation.
