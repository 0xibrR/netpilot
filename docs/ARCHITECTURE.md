# NetPilot Architecture

This document explains how the code is organized and **why**, so it stays easy to understand months from now.

## Layers (MVC)

```text
        ┌─────────────────────────────────────────┐
        │  View        FXML + CSS (resources/)    │  what the user sees
        └──────────────────┬──────────────────────┘
                           │ @FXML bindings
        ┌──────────────────▼──────────────────────┐
        │  Controller  org.netpilot.controller    │  reads input, calls services, shows results
        └──────────────────┬──────────────────────┘
                           │ plain Java calls
        ┌──────────────────▼──────────────────────┐
        │  Service     org.netpilot.service       │  business logic: generate, export
        └──────────────────┬──────────────────────┘
                           │
        ┌──────────────────▼──────────────────────┐
        │  Repository  org.netpilot.repository    │  reads/writes files on disk
        └─────────────────────────────────────────┘
        Model (org.netpilot.model): immutable records and enums used by every layer
```

### Rules

| Rule | Why |
|---|---|
| `model`, `service` and `repository` never import `javafx.*` | Keeps the logic testable without a UI and reusable later (CLI, server) |
| Dependencies only point **down** the diagram | A change in the UI can never break the logic |
| Model types are immutable `record`s | No hidden state changes; safe to share between threads |
| Controllers hold no business logic | All logic lives in services, where it is unit-tested |
| Objects are wired in one place: `AppContext` | Easy to see the whole object graph; easy to swap parts in tests |
| Services take collaborators (and `Clock`, `RandomGenerator`) in the constructor | Tests can pass fakes and get reproducible results |

## Packages

| Package | Contents |
|---|---|
| `org.netpilot` | `Launcher` (entry point), `NetPilotApp` (JavaFX app), `AppContext` (wiring), `AppLogging` |
| `org.netpilot.model` | `HotspotUser`, `GenerationSettings` (+ validation), `AppSettings`, `HistoryRecord`, mode enums; router data: `RouterEndpoint`, `RouterSettings`, `RouterInfo`, `HotspotProfile`, `HotspotServer` |
| `org.netpilot.service` | `UserGenerator`, `ExportService`, and the exporters (CSV, RSC, PDF, QR) |
| `org.netpilot.service.router` | `RouterConnector` (opens sessions), `RouterClient` (interface) + `RestRouterClient` (RouterOS 7 REST API), `PinnedTrustManager`, `RouterErrors`, `RouterException` |
| `org.netpilot.repository` | `SettingsRepository`, `HistoryRepository`, `AppPaths` |
| `org.netpilot.controller` | `MainController`, `HistoryController`, `RouterConnectionController` (dialog), `RouterSession` (status bar + loaded lists), `Dialogs` |

## Main flows

**Generate:** `MainController.onGenerate` → `GenerationSettings.validate()` → `UserGenerator.generate()` → users go to the preview table.

**Export:** `MainController.onExport` starts a background `Task` → `ExportService.export()` writes CSV, RSC and PDF into a new folder → `HistoryRepository.append()`.
The exported users are **exactly** the previewed ones. Changing any setting clears the preview.

**Connect to router:** `RouterSession.showConnectDialog` → `RouterConnectionController` runs `RouterConnector.connect()` in a background `Task` → on success `RouterSession.refresh()` loads profiles and servers → `MainController.showRouterLists` fills the Profile and Server lists.
A first HTTPS connection fails with `CERTIFICATE_UNTRUSTED`. The dialog shows the fingerprint, and if the user trusts it, saves it in `RouterSettings` and retries.

## Where data lives

| What | Where |
|---|---|
| Settings | `~/.netpilot/settings.properties` |
| Export history | `~/.netpilot/history.csv` |
| Logs | `~/.netpilot/logs/netpilot-0.log` (rotated, 5 × 1 MB) |
| Exports | `~/Documents/NetPilot/<timestamp>_<profile>_<count>users/` (configurable) |

## Security principles

- **Escape all output that is interpreted elsewhere.** RouterOS script values are quoted and escaped (`RscExporter.quote`), URLs are encoded (`QrCodeService`), CSV fields are quoted (`Csv`).
- **Validate input at the boundary.** `GenerationSettings.validate()` and `QrCodeService.validateLoginUrl()` run before any work starts.
- **Never log secrets.** Passwords, router credentials and tokens must not reach log files or error messages.
- **Randomness for credentials uses `SecureRandom`.**
- **Router password is never stored.** It lives in a `char[]` that `RouterConnector` wipes after building the auth header. Settings keep only the endpoint and trusted fingerprints.
- **No "trust all certificates".** HTTPS trusts only the certificate whose SHA-256 fingerprint the user confirmed (`PinnedTrustManager`, trust on first use). A changed certificate is refused.
- **Redirects are never followed**, so credentials cannot be sent to another host.

## Quality gates

`mvnw verify` (run locally and on GitHub Actions for every push and pull request) fails when:

| Check | Tool | Fix |
|---|---|---|
| Code is not formatted | Spotless + Eclipse formatter (`config/eclipse-formatter.prefs`) | `mvnw spotless:apply` |
| A unit test fails | JUnit 6 | fix the code or the test |
| Line coverage of model/service/repository is below 80% | JaCoCo | add tests; report in `target/site/jacoco/index.html` |
| A likely bug is found | SpotBugs (accepted exceptions in `config/spotbugs-exclude.xml`, each with a reason) | fix it, or add a justified exclusion |

## Java module

NetPilot is a named module (`src/main/java/module-info.java`). JavaFX officially requires the module path, and a module is needed later for `jlink`/`jpackage` installers.
When adding a library, add its `requires` line. When a new package is used by FXML, `opens` it to `javafx.fxml`.
