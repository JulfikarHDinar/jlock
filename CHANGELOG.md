# Changelog

All notable changes to jLock are listed here. Versions follow [semantic versioning](https://semver.org): MAJOR.MINOR.PATCH.

## [1.0.0] - 2026-10-30

First public release.

### Added
- Lock any app with a PIN, pattern, password or fingerprint. One method is shown first; the others are under "More options".
- Two ways to watch apps: Usage Access (default, works with banking apps) and Accessibility (fastest, also locks chat heads).
- Native lock screen that is up before the locked app shows.
- Lock again when the screen turns off, or as soon as you leave the app.
- jLock locks itself.
- Growing wait after 5 wrong tries (30 seconds up to 5 minutes) that can't be skipped by changing the clock or reopening the app.
- Hidden notifications: chosen apps show only "New notification", with sound and vibration kept.
- Activity log of failed unlocks and hidden notifications.
- Bluetooth, Wi‑Fi and other Settings popups are never locked, so system prompts keep working.
- Starts right after a restart, and drops the lock instead of getting stuck if something goes wrong.
- Setup wizard, six themes, pattern path and vibration options.
- Check for updates: opens the latest release in your browser. jLock itself stays offline.
- Works fully offline: no account, no ads, no tracking and no internet permission.

[1.0.0]: https://github.com/JulfikarHDinar/jlock/releases/tag/v1.0.0
