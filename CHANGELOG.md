# Changelog

All notable changes to NetPilot are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
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
- Possible null path when creating the settings and history folders (found by SpotBugs)
- Login URL validation no longer relies on catching `NullPointerException`

## [1.0.0] - 2026-11-06

### Added
- JavaFX + MVC rewrite of MikroTik Hotspot Manager as NetPilot
- Unique username generation with four username modes and five password modes
- Preview that always matches the export
- CSV, escaped RouterOS script (skips existing users), and A4 PDF vouchers with QR auto-login
- Export history and persistent settings
- Named Java module
