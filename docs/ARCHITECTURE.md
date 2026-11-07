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
| `org.netpilot.service` | `UserGenerator`, `ExportService`, the exporters (CSV, RSC, PDF, QR), `PdfText` (Arabic shaping and bidirectional layout for the PDF), `ReportService` (sales calculation, pure logic) and `ReportExporter` (CSV) |
| `org.netpilot.service.router` | `RouterConnector` (opens sessions), `RouterClient` (interface) + `RestRouterClient` (RouterOS 7 REST API), `PinnedTrustManager`, `RouterErrors`, `RouterException`, `RouterUploadService` (send a batch), `RouterUserActions` (bulk enable/disable/delete/disconnect/reprint) |
| `org.netpilot.repository` | `SettingsRepository`, `HistoryRepository`, `UsageRepository` (first-use day of each card), `AppPaths` |
| `org.netpilot.controller` | `MainController` (main window: top bar, side menu, status bar, page switching), `Page` + `PageId` + `Workspace` (what pages share), pages: `DashboardController`, `GenerateController`, `HotspotController` (tabs; its users tab is `RouterUsersController`), `ReportsController`, `VoucherDesignController`, `HistoryController`, `SettingsController`; dialogs: `RouterConnectionController`, `UploadController`; `RouterSession` (connection + listeners), `Styling` (stylesheets, theme, accent, right-to-left), `Icons`, `Dialogs` |
| `org.netpilot.i18n` | `Messages`: every text shown to the user, from `messages_en.properties` / `messages_ar.properties` |

## Main flows

**Generate:** `MainController.onGenerate` → `GenerationSettings.validate()` → `UserGenerator.generate()` → users go to the preview table.

**Export:** `MainController.onExport` starts a background `Task` → `ExportService.export()` writes CSV, RSC and PDF into a new folder → `HistoryRepository.append()`.
The exported users are **exactly** the previewed ones. Changing any setting clears the preview.

**Connect to router:** `RouterSession.showConnectDialog` → `RouterConnectionController` runs `RouterConnector.connect()` in a background `Task` → on success `RouterSession.refresh()` loads profiles and servers → `MainController.showRouterLists` fills the Profile and Server lists.
A first HTTPS connection fails with `CERTIFICATE_UNTRUSTED`. The dialog shows the fingerprint, and if the user trusts it, saves it in `RouterSettings` and retries.

**Send to router:** `MainController.onSendToRouter` checks the profile and server against the loaded lists and asks for confirmation. Then `UploadController` runs `RouterUploadService.upload()` in a background `Task`:
1. Read existing user names. If this fails, nothing is changed.
2. Create each user with `PUT /rest/ip/hotspot/user`, all with one batch comment.
3. Existing names are replaced through `UserGenerator.replacement()` (random modes) or skipped.
4. Connection problems, or 5 identical failures in a row, stop the upload.

The resulting `UploadReport` drives the summary. **Print** exports only the created users. **Undo** deletes them by their router ids, and the preview then shows exactly what is on the router.

**Manage users:** `RouterUsersController` loads `hotspotUsers()` (without passwords) and `activeSessions()` in one background `Task`, then filters locally with `UserFilter`, so typing never sends requests. Bulk buttons call `RouterUserActions`, which works item by item and stops on errors that affect every request (`RouterException.affectsEveryRequest()`); the list then refreshes. **Print vouchers** reads passwords with one request, for the selected users only, and hands them to the main window's export.

## Where data lives

| What | Where |
|---|---|
| Settings | `~/.netpilot/settings.properties` |
| Export history | `~/.netpilot/history.csv` |
| Logs | `~/.netpilot/logs/netpilot-0.log` (rotated, 5 × 1 MB) |
| Exports | `~/Documents/NetPilot/<timestamp>_<profile>_<count>users/` (configurable) |
| Voucher logo | `~/.netpilot/voucher-logo.png` (a copy, so moving the original does not break the design) |
| Sales history | `~/.netpilot/usage.csv`: one line per used card with the day NetPilot first saw it used |

## How sales are counted

RouterOS does not record when a hotspot user first logged in, so NetPilot keeps its own record:

1. Every time **Reports** refreshes, `ReportService.track` adds cards that are now used but not yet known, dated **today**. Daily accuracy therefore depends on opening Reports regularly.
2. The **first** time a router is tracked, cards that are already used get **no date**. Dating them "today" would show a fake spike. They count in "All time" only, and the report says so.
3. Records stay when cards are deleted from the router, so past revenue never disappears.
4. Revenue = sold cards × the price of their profile. Prices come from the Voucher Design text and are parsed by `Money`. Prices without a number, or in a different currency from the rest, are listed as warnings and left out of the total.

## Main window and pages

- `MainController` builds the WinBox-like frame: top bar (router connection, theme switch), side menu (one entry per `PageId`), a `StackPane` for pages, and a status bar.
- A page is an FXML view whose controller implements `Page`. Pages are loaded the first time they are opened and then kept, so filters and previews survive switching. `Create cards` is loaded at start-up because other pages print vouchers through it (`Workspace.printVouchers`).
- `Workspace` holds what pages share: services, the `RouterSession`, the saved `AppSettings` (`updateSettings` saves at once) and navigation.
- `RouterSession` notifies listeners (`routerConnected`, `routerListsLoaded`, `routerDisconnected`); `MainController` forwards the events to loaded pages and replays them to pages loaded later. Pages marked `needsRouter` show a Connect button while no router is connected.
- The status bar reads `/system/resource` every 5 seconds (`RouterClient.resource()`), never two reads at once.

## Interface language and theme

- Every user-visible text lives in `resources/org/netpilot/i18n/messages_en.properties` and `messages_ar.properties`. Views use `%key` (FXMLLoader gets `Messages.bundle()`); code uses `Messages.get("key", args...)`, which formats with `MessageFormat` (numbers are not grouped, so `1000` never becomes `1,000`).
- The language is chosen once at start-up (`NetPilotApp.start`), before any window is built; changing it in Preferences applies on the next start. Logs stay in English.
- `Styling.apply` is called on every scene and dialog: it adds `styles.css` (plus `dark.css` for the dark theme), sets right-to-left orientation for Arabic and the `arabic` style class (Cairo font). `Styling.setTheme` restyles all open windows; the system theme follows `Platform.getPreferences()`.
- `dark.css` only overrides colour tokens (`-np-*`, `-fx-base`, ...). The voucher preview card keeps white paper colours in both themes.
- Semi-dark adds the `chrome-dark` style class, which only changes the `-np-chrome-*` tokens of the side menu, top bar and status bar. The accent colour is set as `-np-accent` on each root; hover shades are derived from it in CSS.
- `MessagesTest` fails when a key is missing in one language, placeholders differ, a used key does not exist, or a view has text that is not a `%key`.

## Compact voucher cards

- `VoucherStyle` (Classic, Modern, QR on the side, Minimal, My own picture) and `CardSize` (a fixed grid on A4) are part of `VoucherTemplate`; `CardImage` holds the user's picture and where each detail sits on it (fractions of the card, changed by dragging in the preview).
- `CardLayout` turns a template and one user into drawing elements (band, text, QR, picture, logo) in PDF points. `PdfVoucherGenerator` draws them in a cell event per card; the Voucher design page draws the same elements with JavaFX nodes. Text sizes shrink to fit, measured by whichever side draws.
- Classic keeps the original flowing layout and the "vouchers per row" setting.

## Router discovery

`NeighborDiscovery` sends an empty MNDP request (UDP 5678) to the broadcast addresses and reads the routers' answers for 3 seconds: MAC, identity, version, board, uptime, interface and IPv4. It never sends credentials. A router without an IP is listed but cannot be used, because the REST API needs IP.

## Arabic in PDFs

Voucher PDFs embed the **Cairo** font (`resources/org/netpilot/fonts`, SIL Open Font License). `PdfText` handles the text, and each choice in it comes from a test result:

| Problem seen | Fix |
|---|---|
| OpenPDF's classic Arabic mode dropped letters (it uses old "presentation form" characters the font lacks) | Use OpenPDF's `LayoutProcessor`, which shapes text with Java's text engine and the font's own tables |
| Forcing right-to-left reversed numbers and Latin text (`714133` → `331417`) | Split each line into direction runs with `java.text.Bidi`, draw each run in its own direction, and place the runs in visual order |
| The direction setting is stored per font file | Load two copies of the font: one for right-to-left runs, one for left-to-right runs |
| Spaces at the edge between runs disappeared | Strip edge spaces and add explicit space chunks |
| PDF line wrapping, done after reordering, moved Arabic words to the wrong line | Wrap long text in reading order first, measured as OpenPDF measures it, then lay out each line |

Tests cover each of these: `PdfTextTest` and `ExportersTest.arabicVouchersKeepCredentialsInTheirOriginalOrder`.

## Security principles

- **Escape all output that is interpreted elsewhere.** RouterOS script values are quoted and escaped (`RscExporter.quote`), URLs are encoded (`QrCodeService`), CSV fields are quoted (`Csv`).
- **Validate input at the boundary.** `GenerationSettings.validate()` and `QrCodeService.validateLoginUrl()` run before any work starts.
- **Never log secrets.** Passwords, router credentials and tokens must not reach log files or error messages.
- **Randomness for credentials uses `SecureRandom`.**
- **Router password is not stored unless the user asks.** It lives in a `char[]` that `RouterConnector` wipes after building the auth header. Settings keep only the endpoint, trusted fingerprints and the auto-connect choice. With **Remember password** ticked, `PasswordVault` keeps it in `~/.netpilot/credentials`, encrypted by `DpapiProtector` (Windows DPAPI through JNA): only the same Windows account on the same computer can decrypt it, and a copied file is useless elsewhere. Unticking it, or **Forget password**, deletes the entry. On other systems the option is hidden.
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
