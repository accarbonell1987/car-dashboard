# CAROBD

Android head-unit app (module `screen`) plus shared core (`core`) for a Hyundai Accent 2016 1.6 CRDi, read through an ELM327 BLE clone.

## Publishing a test build (the "deploy")

The head unit updates itself from GitHub releases, so a release IS the deploy. Publish only when the user approves it in the thread.

1. **GitHub account.** `gh auth status` must show `accarbonell1987` active. It can flip to `accarbonelld2` (another session moved it); fix it with `gh auth switch -u accarbonell1987`. With the wrong account, the push fails with 403.
2. **Branch.** Work on local `main`; `git pull --ff-only` before starting. Conventional commits, no AI attribution.
3. **Privacy scan.** The repo is public. `git diff origin/main | rg "^\+" | rg -o "KMH[A-Z0-9]{14}"` may only show the redacted test VIN `KMHCU41UAGU000000`. No licence plate in images (the car photo has it blanked).
4. **Tests and build.** `./gradlew :core:jvmTest :screen:assembleDebug -q` must pass.
5. **Version.** `versionCode = git rev-list --count HEAD` (set in `screen/build.gradle.kts`, `versionName` is `0.1.<count>`). Check `screen/build/outputs/apk/debug/output-metadata.json`.
6. **Asset name.** Copy the APK as `car-dashboard-<versionCode>.apk`. The in-app updater (`core/.../actualizacion/Releases.kt`) only looks for that exact name; drafts are ignored, prereleases count.
7. **Publish.**
   ```bash
   git push origin main
   gh release create apk-prueba-<N> car-dashboard-<versionCode>.apk --target main --prerelease --title "apk-prueba-<N>" --notes "<what changed>"
   gh api repos/accarbonell1987/car-dashboard/releases/tags/apk-prueba-<N> --jq '.assets[].name'
   ```
   `<N>` is the last `apk-prueba-*` tag plus one (`gh release list`).
8. **Signing.** Builds are debug builds signed with this Mac's debug keystore. An update only installs over an APK with the same signature, so build releases on this Mac.
9. **On the car.** The app shows an update banner, or Ajustes → Acerca → Buscar actualización. Android asks for one confirmation tap (and the first time, to allow installs from CAROBD).

**Only the release thread publishes.** One dedicated thread of the project (and, in local sessions, the `apk-publisher` agent in `.claude/agents/`) owns every release; no other thread or agent pushes to `main` or creates releases. A development thread that has something ready sends the release thread its branch or PR, what changes and what to verify. The release thread merges it into `main` if it fits, publishes requests one at a time (consecutive `versionCode` and `apk-prueba-<N>`), and reports the result back to the requesting thread. Two sessions releasing from the same `main` would clash on version numbers.

## Rules that are easy to break

- **Engine only.** Never query modules other than the engine (7E0, all requests go there via `ATSH7E0`). Body data is read by passive listening only (`ATMA` + `ATCRA<id>`). Querying other modules once froze the instrument cluster until a power cycle.
- **The BLE clone** sends late prompts after `ATMA`, rejects a bare `ATCRA` (restore with `ATCRA7E8`), and with a response-count suffix returns after the first CAN frame (never use it on multi-frame replies). Test ELM changes against `python3 obd/emulador/elm327_emulador.py --clon-ble`.
- **Diagnosing in the car:** Ajustes → Adaptador → Registro de comandos → "Compartir registro completo" exports a timestamped log the user can attach to the thread.
