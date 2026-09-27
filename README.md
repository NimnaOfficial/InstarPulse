# InstaPulse — Relationship Intelligence Engine

Native Android application built with Kotlin and Jetpack Compose. InstaPulse provides relationship analytics, follower and following tracking, unfollower detection, and safe execution queues.

## Features

- **Cinematic Entrance**: Smooth neon pulse radar animation and anamorphic light-sweep branding.
- **Relationship Analytics**:
  - **Reciprocity %**: Ratio of mutual connections to your total following list.
  - **Follower Ratio**: Follower-to-following health ratio multiplier.
  - **Net Delta**: Live follower count changes.
- **Bento Intelligence Grid**:
  - **Not Following Back ("TRAITORS")**: Track users who do not follow back.
  - **Loyal Followers ("FANS")**: Track fans who follow you without reciprocal follow.
  - **Mutual Connections ("MUTUALS")**: Active two-way friendships.
  - **Action History ("RECENTS")**: Chronological log of recent follows and unfollows with timestamps.
  - **Protected Whitelist**: Mark VIP or essential accounts to safeguard them from unfollow actions.
- **Search & Filtering**: Instant search across usernames and full names with quick filters (`All`, `Verified ✓`, `Private 🔒`, `Public 🌐`) and 5-way sorting (`Default`, `A-Z`, `Z-A`, `Newest`, `Oldest`).
- **Batch Action Queue**: Multi-select accounts with an animated floating HUD glass display, ultra-fast human pacing delay, and pause/resume controls.
- **Instagram Authentication & Sync**: Integrated Web session with cookie detection and background sync engine.

## Tech Stack

- **Platform**: Android SDK 36, Kotlin 2.2.10, Gradle 9.3.1 (Kotlin DSL)
- **UI Framework**: Jetpack Compose, Material 3
- **State Management**: Android ViewModel with Kotlin StateFlow & Coroutines
- **Image Loading**: Coil Compose
- **Persistence**: SharedPreferences with structured JSON serialization
