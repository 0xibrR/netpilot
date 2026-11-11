# Changelog

All notable changes to NetPilot are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.1] - 2026-11-11

### Changed
- First release delivered by NetPilot's own update check: 1.2.0 finds it on Home and installs it after you confirm

## [1.2.0] - 2026-11-11

### Added
- Updates: once a day NetPilot checks GitHub for a newer release and offers it on Home (What's new, Update now,
  Later, Skip this version); the installer is downloaded, checked against the release's SHA-256 list, a backup
  is taken, and it runs only after you confirm. Settings › Updates turns the daily check off or checks now.
  Releases are published by GitHub Actions when a version tag (v1.2.0) is pushed
- Monthly PDF report (Earnings and sales): pick any month with records and the parts to include; A4, right to
  left in Arabic, with the month summary and the change from last month, sales by day, best-selling packages,
  each point of sale for the month (frozen prices and commissions, balance at month end), direct sales,
  batches and notes; same figures as the page, written atomically
- Developer tools: Traceroute (each router on the way, 3 probes a step, live), DNS lookup (through the
  router, following CNAMEs, with its DNS settings read only) and Router log (live, errors red and warnings
  amber, filter, search, save to a file); Ctrl+1…Ctrl+9 keep their pages
- Online now (Manage users): live status cards (online, CPU, memory, total download and upload), a chart of
  the last 5 minutes, and per-user package, time left, download and upload speed and data used; search by
  name, IP or MAC; refresh every second or every 2, 5 or 10 seconds, only while the tab is on screen; disconnecting asks first

## [1.1.0] - 2026-11-09

### Changed
- New identity, "Route N": the letter N as a network path between two nodes, on a flat blue tile; a one-colour
  version and scalable SVGs are in `packaging/brand`
- Every page has its title at the top, like Home and Settings: Manage users, Card design, Earnings and sales,
  and Print history now too
- Side menu in groups that new pages can join: Home; Hotspot management (Create cards, Manage users, Card
  design); Reports and money (Points of sale, Earnings and sales, Print history); Settings. Entries inside a
  group have their own small icon, and `Ctrl+1`…`Ctrl+8` follow the menu order
- Earnings & sales counts only cards created by NetPilot: users made by hand on the router (admins, own
  devices, monthly users) are not sales, so they no longer add to sold cards or revenue or raise the
  "used before recording" warning; under All time they still show as the "Other" row
- The interface language changes at once, without restarting: the window is rebuilt in the new language, the router
  stays connected and the same page stays open
- Arabic texts say "card" (بطاقة) everywhere, matching the menu; unused styles and code removed
- NetPilot opens on the Dashboard; its quick actions use the menu names and icons, and its wording matches the menu
- Manage users shows data and times like WinBox: separate Upload (Bytes In) and Download (Bytes Out) in MiB/GiB,
  and full uptimes such as 2d 09:56:36
- Manage cards: the connection column is titled and shows "Online" in green or "Offline" in red (online first when sorted)
- Own clearer names in the side menu (Card center: New batch, Manage cards (list, online now and search on one page), Earnings, Card look,
  Print history) and flat theme-coloured controls, so dark mode has no
  grey gradients
- Side menu groups everything about cards under a folding "Cards" group (create, card users, online now, search
  for a card, sales, design, export history) and profiles and servers under "Hotspot"
- Stronger card numbers: weak patterns are refused (runs like `7777`, counting like `1234`/`9876`, repeats like
  `1212`), digit-only numbers do not start with 0, and any two cards differ in at least two characters, in the
  batch and against numbers already on the router; separate passwords avoid weak patterns too
- Create cards: one simple "Card" section replaces the username and password mode lists: quantity, number format,
  starts with, ends with, card number length and password (none, any length, same as the card number, or one shared password); sequential and
  profile-based names were removed; saved settings are carried over automatically

### Added
- Developer tools menu group with a Ping page: the connected router pings an IP address or domain (one probe a
  second, a set count or until stopped) through its REST API, showing each reply, sent/received packets,
  packet loss and min/avg/max latency. Runs in a background `PingSession` (package `service.tools`, ready for
  more tools); addresses are checked before they reach the router
- Quick sale on Home: one button per priced package (with the cards left) prints the receipt of the oldest
  ready card. Direct sales have their own ledger entries (DIRECT_RESERVE, DIRECT_SALE, DIRECT_RELEASE), kept
  apart from points of sale and their balances: the card is reserved before printing (never sold twice), the
  sale is recorded once printed, and released if printing fails. A sale left unfinished by a crash or a failed
  save stays pending with its card reserved, is settled from Home (or completes by itself once the card is
  used) and is never recorded twice. Without a receipt printer the card is shown on screen. F2 repeats the last
  package, only when no dialog is open and no text is being typed
- Platform layer, ready for macOS: one place detects the system and processor (Intel and Apple Silicon
  Macs), `AppDirectories` gives each platform its own data, config, cache and log folders (Windows keeps
  `~/.netpilot`), `CredentialStore` keeps the remembered router password in the existing DPAPI file on
  Windows and in the Keychain on macOS, and receipt printing sits behind a common interface. Windows behaves
  exactly as before; the macOS package itself comes later
- Backup and restore (Settings): one file with settings, card design and pictures, sales history, print
  history and the points of sale ledger, optionally encrypted with a password (AES-256-GCM); automatic daily
  backups keeping the last 14. Backups are written atomically and checked before they get their name; a
  restore checks format and every SHA-256 first, warns when newer transactions would be lost, saves the
  current data, swaps all files as one step (rolled back on failure, undone at start after a power cut) and
  relinks the pictures on another computer. See docs/BACKUP.md
- Points of sale: hand cards to resellers (from the new page, Manage users or after sending a batch), see
  what each received, sold, paid and still owes, record partial payments and cancel mistaken ones, take back
  unsold cards and export a statement. Everything comes from an append-only ledger
  (`reseller-ledger.csv`): each handover freezes the card's price and commission, a sale (first use) is
  recorded once and takes its figures from its handover, and nothing is ever edited or removed, so the
  accounts stay right after price or commission changes and after cards are deleted from the router
- Data-limit cards: an optional data limit (MB/GB, upload and download together) on Create cards, sent as
  RouterOS `limit-bytes-total` and in `.rsc` scripts; a card ends when its time or its data runs out first,
  Manage users shows the limit, cards and receipts print it, and "Delete expired" and the automatic clean-up
  include cards whose data ran out
- Low-stock alert: the dashboard lists packages with fewer unused NetPilot cards than the limit set in
  Settings (default 10, 0 = off), each with a button that opens Create cards on that package; a short notice
  appears once when a package runs low
- Automatic clean-up (Settings): NetPilot deletes expired cards once a day at the chosen time while it is
  connected, or at the next connection after a missed day; their sales are saved first, the same way as
  "Delete expired", and the last run is shown
- Receipt printing on 58/80 mm thermal printers through the printer's Windows driver: one receipt per card
  ("Print receipt" on Manage users, "Print receipts" after sending a batch), printer and paper width in Card
  design with a live receipt preview and a test print; A4 PDF printing is unchanged
- Windows installer (`NetPilot-<version>.exe`) and portable zip, both with their own Java: built by the
  new **Windows installer** GitHub workflow on every push to master, or locally with
  `mvnw clean -Pinstaller package` (see docs/INSTALLER.md)
- NetPilot icon for the program, its shortcuts and the window title bar
- Card validity counted from the first login (for example 7 days), set per package in the package editor:
  the router enforces it itself through the package's on-login script, so it works while NetPilot is closed;
  Manage users shows when each card ends, and a warning appears when the router does not take its time from
  the internet
- "Delete expired" on Manage users removes every card that used up its time, after one confirmation;
  their sales are saved first, so Earnings & sales still counts them
- Ready for development on macOS: CI also builds on macOS, and `.idea/misc.xml` (local JDK name) is no longer tracked
- Your own speeds: no built-in speed list any more; **Hotspot → User profiles** adds, edits and deletes profiles
  with any download/upload speed (k, M or G, e.g. 1 G) and devices per card
- Live updates like WinBox: while a router is connected, the visible page refreshes every 5 seconds (users,
  online sessions, profiles; dashboard every 15 seconds), keeping filters, selection and folded batches;
  "● Live" in the status bar
- Full date and time in the status bar
- Remember password (optional, off by default): encrypted with the Windows account through DPAPI in
  `~/.netpilot/credentials`, never in the settings; **Forget password**; optional automatic connection at start-up
- Compact cards, router discovery and a modern look:
  - New voucher styles: Modern (coloured band), QR on the side, Minimal (code only) and My own picture;
    sizes Tiny (65 per A4 page), Small (40) and Medium (24), with light cut lines; the Classic style stays
  - Upload your own card picture and drag the username, password, price and QR to where it has room
  - The preview and the PDF are drawn from the same layout (`CardLayout`), so they always match
  - Connect dialog lists MikroTik routers on the network (MNDP, like WinBox Neighbors); click one to use it
  - The top bar takes the chosen main colour; softer cards, button icons, first-run steps on the dashboard and
    short messages that fade out for results such as "Voucher design saved"
- Phase 7, WinBox-style interface:
  - One main window with a side menu instead of separate windows: Dashboard, Create cards, Hotspot, Reports,
    Voucher design, Export history and Settings (`Ctrl+1` … `Ctrl+7`)
  - Top bar with the router connection (connect, reload lists, disconnect) and a theme switch
  - Status bar with the router's model, RouterOS version, CPU load, memory and uptime, refreshed every 5 seconds
  - Dashboard: cards on the router, online now, never used, expired, revenue this month, money still to come
  - Hotspot page with WinBox-like tabs: users grouped by batch with a toolbar, active sessions (disconnect),
    user profiles (with their price) and servers
  - Semi-dark theme (dark menu, light content) and seven main colours to choose from
  - Pages that need a router show a Connect button while none is connected
- Phase 6, Arabic interface and themes (**Tools → Preferences…**, `Ctrl+,`):
  - The whole interface in English or Arabic: menus, windows, dialogs, router and validation messages, report CSV headers
  - Arabic uses a right-to-left layout and the Cairo font; the language applies on the next start
  - Light, dark, or the system theme (followed live); the voucher preview stays white like paper
  - First start picks Arabic on an Arabic system, English otherwise
  - Tests keep both languages complete: same keys, same placeholders, no untranslated text in the views
- Phase 5, sales reports (**Router → Reports…**, `Ctrl+T`):
  - Revenue, money still to come from unused cards, cards sold, never used and created, for a chosen period
  - Revenue-per-day chart, plus tables by batch and by profile (with data used)
  - A card counts as sold on the first day NetPilot sees it used; that day is kept in `~/.netpilot/usage.csv`,
    so deleting cards later does not erase past revenue
  - Prices are read from Voucher Design text (`1000 ل.س`, `١٠٠٠ ل.س`, `$5`, `2,500 SYP`); unreadable prices and
    other currencies are flagged instead of being added into a wrong total
  - Export to CSV (UTF-8, opens correctly in Excel)
- Phase 4, voucher design and Arabic (**Tools → Voucher Design…**, `Ctrl+D`):
  - Network name, logo, accent color, note, label language (English / Arabic) and what to show (QR, time, price, profile)
  - Price per profile, printed on the voucher (and ready for sales reports)
  - Live preview card and **Test print** with the exact PDF layout
  - Arabic in the PDF: joined letters, right-to-left lines, correct word wrapping, embedded Cairo font (SIL Open Font License)
  - Numbers and Latin credentials inside Arabic lines keep their order (regression-tested)
  - Vouchers without a QR code are shorter, so more fit on a page
- Phase 3, manage router users (**Router → Manage Users…**, `Ctrl+U`):
  - List of all hotspot users with used time, limit, data, state, comment and a live online marker
  - Search plus filters by NetPilot batch, profile and state (online, never used, expired, disabled)
  - Bulk Enable, Disable, Disconnect, Delete (with confirmation) and Print vouchers for the selection
  - Passwords are never listed; they are read only to reprint the selected vouchers
- Phase 2, send users to the router:
  - **Send to Router** creates the previewed users directly on the connected router, with a progress bar and Cancel
  - Existing names get a new random name (random modes) or are skipped (sequential modes); nothing is overwritten
  - Every batch is tagged with a comment such as `NetPilot 2026-11-06 21:40 #a7f3`
  - Summary with created / replaced / skipped / failed counts, **Print vouchers** for exactly the created users, and **Undo**
  - Checks that the profile and server exist on the router before sending; warns when the connection is plain HTTP
  - Stops on connection or permission problems, or after 5 identical failures in a row
- Phase 1, router connection (RouterOS 7 REST API):
  - **Router → Connect…** dialog with Test and Connect; status bar shows the connected router
  - Hotspot profiles and servers are loaded from the router into the form
  - HTTPS with certificate pinning (trust on first use); a changed certificate is refused
  - The router password is never saved; only address, port, user and trusted fingerprints are
  - Clear messages for wrong password, missing policy, unreachable router, timeouts and HTTPS problems
  - `docs/ROUTER_SETUP.md`: limited router user, HTTPS certificate, troubleshooting
- Phase 0, quality foundation:
  - GitHub Actions builds and checks every push and pull request on Linux and Windows
  - Spotless code formatting, JaCoCo coverage gate (80%), SpotBugs static analysis
  - Rotating log files in `~/.netpilot/logs` and a dialog for unexpected errors
  - `docs/ARCHITECTURE.md`, `docs/CONTRIBUTING.md`, `.editorconfig`, `.gitattributes`

### Changed
- JavaFX 27.0.1, Maven 3.10.0, and newer Maven plugins and GitHub Actions
- Dependency updates are applied manually; Dependabot version-update PRs are disabled

### Fixed
- A card on its first login now counts as used at once: RouterOS adds a session's uptime to the user only
  when it logs out, so first use (Earnings, points of sale) also looks at the active sessions. Points of sale
  read the router on Refresh, when opened and every 15 seconds, and log each recorded sale and any failed sync
- Possible null path when creating the settings and history folders (found by SpotBugs)
- Login URL validation no longer relies on catching `NullPointerException`

## [1.0.0] - 2026-11-06

### Changed
- Stronger card numbers: weak patterns are refused (runs like `7777`, counting like `1234`/`9876`, repeats like
  `1212`), digit-only numbers do not start with 0, and any two cards differ in at least two characters, in the
  batch and against numbers already on the router; separate passwords avoid weak patterns too
- Create cards: one simple "Card" section replaces the username and password mode lists: quantity, number format,
  starts with, ends with, card number length and password (none, any length, same as the card number, or one shared password); sequential and
  profile-based names were removed; saved settings are carried over automatically

### Added
- JavaFX + MVC rewrite of MikroTik Hotspot Manager as NetPilot
- Unique username generation with four username modes and five password modes
- Preview that always matches the export
- CSV, escaped RouterOS script (skips existing users), and A4 PDF vouchers with QR auto-login
- Export history and persistent settings
- Named Java module
