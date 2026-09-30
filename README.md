# Swipe Gallery

A native Android app for cleaning up a photo gallery one swipe at a time.

- **Swipe left**: the photo goes into a pending **review queue**. Nothing is deleted.
- **Swipe right**: keep the photo.
- Photos only move to the **device trash** after you confirm in **Android's own system dialog** (`MediaStore.createTrashRequest`). The app never deletes permanently.

Kotlin, Jetpack Compose (Material 3, heavily restyled), Coroutines/StateFlow, Room, DataStore, Coil 3 and Google Play Billing. Min SDK 30 (Android 11), target/compile SDK 36. There is no login, backend, analytics, ads or cloud, and the app's own manifest doesn't declare `INTERNET` (check the merged manifest after the first build to confirm no dependency adds it).

---

## Build and test status

| What | Status |
|---|---|
| `domain` module (quota ledger, review engine, undo, session planning, swipe thresholds, entitlement rules, trash reconciliation) | **Compiled, and 42 JVM unit tests passed** here with `./gradlew -p domain test` (Gradle 8.14.3, JDK 21). |
| `app` module (Android/Compose/Room/Billing) | **Not compiled here.** This environment blocks `dl.google.com` / `maven.google.com`, so there is no Android SDK, AGP or AndroidX. The Kotlin sources passed a syntax-level check (ktlint parse), and I reviewed them by hand for API correctness. The first build in Android Studio is still the real compile check. |
| Instrumentation / Compose UI tests (`app/src/androidTest`) | Written, **not run**. |
| Device testing, Play purchase testing, Play review | **Not done.** Nothing here claims otherwise. |

On the first Android Studio sync, check the version-sensitive APIs listed under [Things to check on the first compile](#things-to-check-on-the-first-compile).

---

## Project layout

```
settings.gradle.kts           includes :app and the standalone `domain` build (includeBuild)
gradle/libs.versions.toml     single version catalog (shared with domain/)
domain/                       pure Kotlin (JVM). No Android dependency. Unit-tested.
  review/ReviewEngine.kt      atomic, idempotent decisions + daily ledger + queue + undo
  allowance/Allowance.kt      FREE_DAILY_REVIEWS = 50, charge policy
  session/Session.kt          scopes (all / month / album / large), ordering, planning
  swipe/SwipeDecider.kt       commit threshold (30% width), fling rules, tilt, label progress
  billing/Entitlement.kt      PURCHASED-only grants, offline-safe caching, acknowledgement
  trash/Trash.kt              pre-flight probe, batching, post-dialog reconciliation
  time/AppClock.kt            injectable clock + local-day flow
app/
  data/db/                    Room entities, DAO, RoomReviewStore (engine ↔ SQLite transaction)
  data/media/                 MediaStore index, permissions, probing, trash requests
  data/billing/               Play Billing client, entitlement cache (DataStore)
  data/prefs/                 DataStore preferences (theme, onboarding, haptics, …)
  data/review/                Read models, sessions, daily allowance flow
  ui/…                        Screens + ViewModels (Home, Albums, Setup, Session, Review, Paywall, Settings, Privacy, Onboarding)
  ui/session/SwipeDeck.kt     the gesture + animation deck
  src/debug/…/DebugTools.kt   debug-only "simulate Premium" toggle (not in release)
  src/release/…/DebugTools.kt release stub: constant false, no UI
```

Dependencies are wired by hand in `AppContainer` (no DI framework). Composables contain no storage, billing, quota or deletion logic. They render ViewModel state and forward user intent.

---

## Build and run

Requirements: a recent stable Android Studio, JDK 17+, and Android SDK platform 36.

```bash
./gradlew :app:assembleDebug                 # debug APK
./gradlew :app:installDebug                  # install on a connected device/emulator
./gradlew -p domain test                     # business-rule unit tests (no Android SDK needed)
./gradlew :app:connectedDebugAndroidTest     # instrumentation + Compose UI tests (device required)
```

### Toolchain

Catalog: `gradle/libs.versions.toml`. AGP 8.12.0, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2025.08.00, Room 2.7.2, DataStore 1.1.7, Lifecycle 2.9.2, Navigation 2.9.3, Coil 3.3.0, Play Billing 8.0.0, Coroutines 1.10.2, Gradle 8.14.3.

I picked these as a known mutually compatible set. I could only confirm the Maven Central artifacts (Kotlin, KSP, Coil, Coroutines) from here; Google Maven was unreachable. Newer stable versions may exist, including AGP 9 and later Billing 8.x/9 releases. Use the Android Studio upgrade assistant if you want them. AGP 9 changes Kotlin plugin setup, so upgrade deliberately.

### Things to check on the first compile

I wrote these API uses from knowledge of the listed versions without compiling them:
- Billing 8: `queryProductDetailsAsync` callback receives `QueryProductDetailsResult` (`productDetailsList`); `enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts())`; `ProductDetails.oneTimePurchaseOfferDetails`.
- Coil 3: `AsyncImage(…, onState = …)`, `ImageRequest.Builder.size(w, h)` / `precision(…)`.
- `material-icons-extended` 1.7.8 icon names (e.g. `Icons.Outlined.PhotoSizeSelectLarge`, `Icons.AutoMirrored.Outlined.Undo`).
- Room schema export goes to `app/schemas/` (commit the generated JSON).

---

## How the core rules work

### Free daily limit (exactly 50)
- A free user may review **50 distinct photo versions per local calendar day**. Keep and "add to queue" both count 1.
- The ledger (`daily_charges`, keyed by `(day, mediaKey)`) is written **in the same SQLite transaction** as the decision, the queue item and the session's undo entry (`ReviewEngine.commit`). A crash leaves either all of it or none of it. A retry is idempotent, so there is no double charge and no missing queue item.
- Recommitting a photo already charged today is free. **Undo does not refund**, but undo followed by a new decision costs nothing more that day.
- Not charged: cancelled swipes, undo, viewing details, opening/deselecting in the queue, "Keep instead", the system trash dialog, purchase restoration, permission handling.
- The day comes from an injectable `AppClock` using the device's time zone. `dayFlow()` re-evaluates at local midnight and after clock or zone changes.
- A new day resets only the allowance. Queue, history, resumable sessions and Premium are untouched.
- **Limitation (by design, offline app):** the device clock is trusted. Changing the clock or clearing app data can change the allowance. The app doesn't claim to be tamper-proof.

### Stable media identity
`MediaKeys.of(volume, id, GENERATION_ADDED, GENERATION_MODIFIED, …)` (API 30 generation counters) identifies a photo *version*. A reused row ID or an edited file gets a new key, so stale decisions never attach to a different file. When a queued file changes after it was queued, the file is **returned for review rather than trashed unseen**.

### Safe removal
1. The review queue is a list of *suggestions*, not an app-owned bin.
2. On "Move N photos to device trash", each selected item is probed in MediaStore (`QUERY_ARG_MATCH_TRASHED`). Items already trashed, deleted elsewhere, edited, or not shared (selected-photos access) are separated first.
3. The rest goes to `MediaStore.createTrashRequest(…, true)` in batches of ≤250 URIs. The platform documents no fixed maximum; batches keep each Binder transaction small. The app launches the system confirmation with `StartIntentSenderForResult` and never hides or replaces it.
4. After the dialog, every URI is **re-probed**. Only items actually in the trash leave the queue. Cancel leaves the queue intact and stops later batches. Partial success and missing items are reported separately.
5. The app never falls back to permanent deletion. The UI never says "storage freed". It says photos were moved to the device trash, notes that storage may not be freed right away, and says cloud copies are unaffected.
6. After a successful trash, in-app undo is refused with an explanation that points to the device's own trash management.

### Photo access
| Android | Permission requested |
|---|---|
| 11–12 (API 30–32) | `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) |
| 13 (API 33) | `READ_MEDIA_IMAGES` |
| 14+ (API 34+) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` (full, selected, or denied) |

The app doesn't request `READ_MEDIA_VIDEO`, `MANAGE_EXTERNAL_STORAGE`, `ACCESS_MEDIA_LOCATION`, camera, contacts, location, microphone or accessibility permissions, and its own manifest doesn't declare `INTERNET`. Access is explained on Home before the system dialog, and requested only from a tap. After a permanent denial the app offers "Open settings" instead of asking again. With "Selected photos only", the app says so on every surface that counts photos and offers **Manage** (re-request → system selection sheet). Access is re-checked on every resume.

### Billing (one-time Premium)
- Product: a **one-time product** with ID `swipe_gallery_premium` (configurable). The app acknowledges it and **never consumes** it.
- Premium is granted only for `PURCHASED`, never for `PENDING`. The confirmed entitlement is cached in DataStore for offline use.
- A failed or offline query never revokes the cache. A successful full `queryPurchasesAsync` is authoritative: a refunded or revoked purchase disappears from it and Premium is removed.
- Purchases are re-queried on app start, on every resume, on reconnect, and from "Restore purchase".
- The price shown is the localized `formattedPrice` from Play. No price is hardcoded.
- **Billing limitations:** verification is client-side only. No backend verifies purchase tokens, so there is no server-grade fraud protection and no Real-time Developer Notifications handling. No service-account secrets ship in the app. If you need stronger guarantees, add a backend that verifies tokens with the Play Developer API.
- A debug-only "simulate Premium" switch lives in `src/debug` and is not compiled into release builds.

---

## Google Play configuration

### 1. In-app product
Play Console → *Monetize → Products → One-time products*: create `swipe_gallery_premium` (or set `swipegallery.premiumProductId`). Add a purchase option with a price and activate it. Products load only for builds uploaded to a testing track and installed by a tester account (use **internal testing** plus **license testers**).

Suggested purchase test plan (not yet performed):
- Successful purchase → Premium active, purchase acknowledged, still active offline (airplane mode + relaunch).
- "Slow test card, approves/declines after a few minutes" → pending state, **no** Premium until approved.
- User cancels the purchase sheet → "Purchase cancelled", free features unaffected.
- Refund/revoke in Play Console → Premium removed after the next successful online query.
- Uninstall/reinstall → Restore purchase.

### 2. Photo and video permissions declaration (required)
Google Play limits `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` to apps whose **core functionality** needs broad access to photos. Other apps must use the system photo picker. Because this app requests `READ_MEDIA_IMAGES`, you must complete the **Photo and video permissions** declaration in Play Console (*App content*) and justify it.

Suggested justification (edit to taste): *"Swipe Gallery is a gallery-cleanup app. Its core feature presents every photo in the user's library, or in chosen albums or months, one at a time so the user can keep it or queue it for removal to the system trash. That requires reading the full image collection and its MediaStore metadata. A one-time picker selection cannot provide this. The app supports Android 14 partial access and works with only the selected photos when the user chooses that."*

**Approval isn't guaranteed.** Google decides based on the store listing, the declaration and app review. Be ready to send a demo video of the core flow. The app also works with partial ("selected photos") access.

### 3. Data safety form
The app collects no data, shares none, and sends nothing off the device. Photos, thumbnails, file names and review history stay local, and the database is excluded from cloud backup and device transfer (`data_extraction_rules.xml`, `allowBackup=false`). Google Play handles purchases. Check your answers against Google's current form wording.

### 4. Other listing items
Content rating questionnaire, target audience, a privacy policy URL (required when requesting sensitive permissions such as photo access), and store assets.

---

## Release configuration (developer-owned)

Set these in `~/.gradle/gradle.properties` or pass them with `-P`. Empty values **hide** the related UI instead of pointing at invented destinations.

| Property | Used for | If empty |
|---|---|---|
| `swipegallery.supportEmail` | Settings → Help & feedback (mailto) | Row hidden |
| `swipegallery.privacyUrl` | Paywall + Privacy screen link | Link hidden (the in-app privacy summary still shows) |
| `swipegallery.termsUrl` | Paywall + Privacy screen link | Link hidden |
| `swipegallery.premiumProductId` | Play product ID | Defaults to `swipe_gallery_premium` |

### Outstanding before release
1. **Compile and run** the app module in Android Studio, and fix anything the first build reports (see the checklist above).
2. Run `connectedDebugAndroidTest` on a phone (compact screen) and a tablet, and test with font scale 200% and "Remove animations".
3. **Signing:** create an upload key and configure `signingConfigs` (deliberately left out of the repo). Enable Play App Signing.
4. Pick the final **applicationId** (currently `com.swipegallery.app`). It can't change after publishing.
5. Supply **support email, privacy policy URL, terms URL** (table above) and host the privacy policy.
6. Create and activate the **one-time product** in Play Console, then run the purchase test plan.
7. Submit the **Photo and video permissions** declaration and the **Data safety** form.
8. Replace the placeholder launcher icon (`res/drawable/ic_launcher_*.xml`) with final artwork if desired.

---

## Tests

**JVM (`domain/src/test`, runs anywhere, currently passing: 42 tests)**
- Reviews 1–50 succeed; review 51 is blocked for Free; Premium bypasses the limit.
- Keep and pending-remove each count once; duplicate and concurrent commits charge once.
- Cancelled gestures don't count (swipe decider + engine).
- Undo/recommit doesn't double-charge; after exhaustion, recommitting an undone photo is still allowed.
- Date rollover resets the allowance and keeps pending photos; time-zone changes move the local day; DST-safe reset time.
- App restart (new engine on the same store) keeps usage; a simulated crash mid-transaction leaves no partial writes.
- Reset review history keeps the daily ledger and the queue.
- Billing failure doesn't revoke cached Premium; pending purchases don't unlock; authoritative empty query revokes; acknowledgement rules.
- Cancelled trash preserves the queue; partial success updates only trashed items; batching; missing/changed/inaccessible handling.
- Externally deleted or changed photos leave the queue without errors.
- Session planning: month scope, album scope, reviewed-photo exclusion and revisit, ordering, unknown sizes.

**Instrumentation (`app/src/androidTest`, written, not yet run)**
- `RoomReviewEngineTest`: the same quota/undo/reset/trash rules against real Room transactions.
- `SwipeDeckTest`: left = remove, right = keep; short and vertical gestures don't decide; buttons match gestures; rapid taps commit once; a refused decision snaps back.
- `OnboardingAndLayoutTest`: onboarding replay keeps usage and preferences; the limit state keeps the queue reachable on a 320×480 dp screen at 200% font; decision buttons stay visible at 200% font.

---

## Accessibility and motion
- All interactive targets are at least 48 dp. Keep/Remove are always available as labeled buttons, and as TalkBack custom actions on the card.
- Keep/remove use icons and words, never color alone. Photo descriptions list only date, size and dimensions, never guessed content.
- Headings (40–48sp) wrap instead of clipping at large font sizes. The session screen switches to a side-by-side layout in short landscape windows.
- With system animations off ("Remove animations"), the deck uses a short fade with no tilt, and screen transitions become instant.

## Fonts
Instrument Serif and Inter are bundled under `app/src/main/res/font`, with the SIL Open Font License 1.1 texts in `app/src/main/assets/licenses/` (also viewable in-app: Settings → Privacy & terms → Font licenses). The TTFs were converted from the `@fontsource` web builds, with latin and latin-ext subsets merged. Neither family declares a Reserved Font Name.
