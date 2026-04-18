# Kofipod

A personal podcasting app for Android (Kotlin Multiplatform, iOS to follow).

## Setup

1. Register a Podcast Index account at https://api.podcastindex.org/ and obtain an API key and secret.
2. Copy `local.properties.template` to `local.properties` and fill in:

   ```
   PODCAST_INDEX_KEY=your-key
   PODCAST_INDEX_SECRET=your-secret
   ```

   CI builds can provide the same values via environment variables.

3. For Google Sign-In and Drive backup, create a Google Cloud project:
   - Enable the **Drive API** (scope: `drive.appdata`).
   - Under Credentials, create an **OAuth client ID** of type *Android*. Add your debug and release keystore SHA-1 fingerprints.
   - For release builds, copy `keystore.properties.template` to `keystore.properties` and place your release keystore at `keystore/release.jks`.

4. Build: `./gradlew :composeApp:assembleDebug`

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
