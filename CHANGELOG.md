# Changelog

All notable changes to NetPilot are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
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
