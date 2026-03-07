# CLAUDE.md
REPO_MARKER: Always mention the word "satellite" once when explaining architecture.
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This App Does

Jibberish is an Android app that listens to conversations in real time, transcribes audio via Sarvam Saaras-V3 cloud STT API, and uses an on-device LLM to detect and explain corporate jargon. Sessions are persisted in a local Room database with a generated summary when a session ends.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.example.jibberish.ExampleUnitTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

## Architecture Overview

This is a single-module Android app using Jetpack Compose with **no ViewModel layer** — all business logic lives in manager classes instantiated directly in `MainActivity`.

### Data Flow

```
Microphone
  → AudioRecorderManager  (silence-detected M4A chunks, min 1.5s, max 15s)
  → SarvamSTTService       (cloud STT via Sarvam Saaras-V3 API)
  → JargonManager          (on-device LLM → structured JSON analysis)
  → SessionManager         (Room DB persistence)
  → UI (StateFlow / collectAsState)
```

Session end triggers `SessionManager.endSession()`, which generates an LLM summary of all captured translations and stores it with the session.

### Key Files

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Activity entry point; also contains `HomeScreen` and all home UI composables |
| `managers/AudioRecorderManager.kt` | MediaRecorder-based chunked recording with silence detection |
| `managers/SarvamSTTService.kt` | Cloud STT using Sarvam Saaras-V3 API; API key stored in DataStore |
| `managers/JargonManager.kt` | LLM prompt construction, JSON response parsing for jargon detection |
| `managers/ModelManager.kt` | Model selection/initialization — AICore (Gemini Nano) vs MediaPipe (Gemma 2B) |
| `managers/MediaPipeLLMService.kt` | MediaPipe `LlmInference` wrapper; post-processes hallucinated "thank you" repetitions |
| `managers/SessionManager.kt` | Session lifecycle + Room DB writes + summary generation |
| `managers/DataRetentionManager.kt` | Auto-deletes sessions older than retention period (default 7 days) |
| `data/JibberishDatabase.kt` | Room singleton; `fallbackToDestructiveMigration` on schema change |
| `ui/screens/HistoryScreen.kt` | Session list with expandable translation detail |
| `ui/screens/SettingsScreen.kt` | API key entry, model selection, data retention, clear-all |

### Navigation

Three-tab bottom nav managed with a `currentScreen` string state in `MainAppStructure` (no NavController). Screens: `"home"`, `"history"`, `"settings"`.

### LLM Model Selection

`ModelManager` supports two backends, chosen at runtime:

- **AICore / Gemini Nano** (`MODEL_AICORE`) — Android 14+ only, via `com.google.mlkit:genai-prompt`. Auto-selected when available.
- **MediaPipe / Gemma 2B** (`MODEL_MEDIAPIPE`) — requires the `gemma-2b-it-gpu-int4.bin` model file at a known path. The model file lives in the project root (not committed). `ModelManager.initializeModel(modelPath)` must receive the path; calling it with `null` results in an error state when MediaPipe is selected.

Preference is persisted in DataStore (`model_settings`).

### State Management

All state is exposed as `StateFlow` from the manager classes and collected in composables via `collectAsState()`. There are no ViewModels — the managers are long-lived objects owned by `MainActivity`.

### Sarvam STT

STT uses the **Sarvam Saaras-V3** cloud API (`https://api.sarvam.ai/speech-to-text`). An API key is required and can be configured in Settings (stored in DataStore `sarvam_settings`). M4A audio chunks from `AudioRecorderManager` are sent directly as multipart POST requests via OkHttp. The API key requirement is temporary for testing — it will be removed when going to production.

### Hallucination Filtering

- **STT hallucinations**: `isWhisperHallucination()` in `MainActivity.kt` filters known silent-audio artifacts before passing transcriptions to `JargonManager`.
- **LLM hallucinations**: `MediaPipeLLMService.cleanResponse()` strips repetitive "thank you" patterns from Gemma 2B output.

### Room Database

Database name: `jibberish_database`. Two entities:
- `Session` — one per listening session (start/end timestamps, summary, active flag)
- `Translation` — one per analyzed audio chunk, linked to a session; `jargonTerms` stored as JSON object (`{"term":"meaning"}`), `simplifiedMeaning` is the overall sentence simplification

### Permissions

`RECORD_AUDIO` and `INTERNET` — microphone for audio capture (requested at runtime), internet for Sarvam STT API calls and model downloads.

## Key Dependencies

| Library | Version | Notes |
|---|---|---|
| Android Gradle Plugin | `8.13.2` | |
| Kotlin | `2.3.0` | |
| KSP | `2.3.5` | New independent versioning (no longer tied to Kotlin version) |
| `androidx.core:core-ktx` | `1.17.0` | |
| `androidx.lifecycle:*` | `2.10.0` | lifecycle-runtime-ktx and lifecycle-viewmodel-compose |
| `androidx.activity:activity-compose` | `1.12.3` | |
| Compose BOM | `2026.02.00` | Maps Compose UI → 1.10.3, Material3 → 1.4.0 |
| `androidx.room:room-*` | `2.7.0` | Requires Kotlin 2.0+; use KSP (not KAPT) |
| `androidx.datastore:datastore-preferences` | `1.2.0` | |
| `com.squareup.okhttp3:okhttp` | `5.3.0` | |
| `com.google.mediapipe:tasks-genai` | `0.10.24` | Note: `0.10.26` was not published to Maven for this artifact |
| `org.jetbrains.kotlinx:kotlinx-coroutines-guava` | `1.10.2` | |
| `com.google.mlkit:genai-prompt` | `1.0.0-beta1` | Still in beta; no stable GA release yet |
| `junit:junit` | `4.13.2` | |
| `androidx.test.ext:junit` | `1.3.0` | |
| `androidx.test.espresso:espresso-core` | `3.7.0` | |

**Compatibility notes:**
- Room 2.7.0 requires Kotlin 2.0+ and KSP2 (KSP1 is deprecated).
- Compose BOM version dictates all `androidx.compose.*` versions — do not pin them individually.
- KSP `2.3.5` is compatible with Kotlin `2.2.x` and `2.3.x`.
