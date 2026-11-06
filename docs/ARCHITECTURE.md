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
| `org.netpilot.model` | `HotspotUser`, `GenerationSettings` (+ validation), `AppSettings`, `HistoryRecord`, mode enums |
| `org.netpilot.service` | `UserGenerator`, `ExportService`, and the exporters (CSV, RSC, PDF, QR) |
| `org.netpilot.repository` | `SettingsRepository`, `HistoryRepository`, `AppPaths` |
| `org.netpilot.controller` | `MainController`, `HistoryController`, `Dialogs` |

## Main flows

**Generate:** `MainController.onGenerate` → `GenerationSettings.validate()` → `UserGenerator.generate()` → users go to the preview table.

**Export:** `MainController.onExport` starts a background `Task` → `ExportService.export()` writes CSV, RSC and PDF into a new folder → `HistoryRepository.append()`.
The exported users are **exactly** the previewed ones. Changing any setting clears the preview.

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
