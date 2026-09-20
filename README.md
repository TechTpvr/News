# Nabz | نبض — Stage 9

Version 1.9.0 — Release Candidate / QA preparation.

## Stage 9 goals
- release/debug APK build workflow
- Android 12 / MIUI 14 QA checklist
- network failure and retry verification
- notification and exact-news deep-link verification
- translation, saved news and offline-cache verification
- source/image/link validation
- final permission and release-readiness pass

## QA checklist
- [ ] App launches on Android 12
- [ ] News refresh works online
- [ ] Offline cache appears after network loss
- [ ] Retry works after a failed refresh
- [ ] Search/category filters work
- [ ] Save/unsave persists after restart
- [ ] English translation works after model download
- [ ] USD card handles unavailable data gracefully
- [ ] Notification opens the selected story
- [ ] Multi-source clustering displays correctly
- [ ] Share action works
- [ ] External source link opens correctly
- [ ] MIUI battery/autostart restrictions are documented

## Build
GitHub Actions builds both debug and release-unsigned APK artifacts.
