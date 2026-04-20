# Current Task

Implement NotificationListenerService + integrate with DetectionRepository

# Requirements

* ScamShieldNotificationService = Intercepts notifications from target apps
* Trigger detection on incoming notifications via DetectionRepository
* Foreground service handling for Android 8.0+ background limits
* Scam alert notification to user when scam detected
* Lightweight — no UI blocking, battery-efficient

# Constraints

* Max 300 lines per file
* MVVM + lifecycle-safe
* Hilt DI
* Coroutine-based
* API 26+ (Android 8.0)
