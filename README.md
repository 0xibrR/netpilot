# NetPilot

[![Build](https://github.com/0xibrR/netpilot/actions/workflows/build.yml/badge.svg)](https://github.com/0xibrR/netpilot/actions/workflows/build.yml)

Network management toolkit. It currently generates MikroTik Hotspot users, printable QR vouchers, and RouterOS import scripts.

Built with **Java 25**, **JavaFX 27**, and **Maven**, using an MVC architecture.

## Features

- Generates hotspot cards with **guaranteed-unique** card numbers from a cryptographic random source; weak patterns (`1111`, `1234`, `1212`) are refused and **any two cards differ in at least two characters**, so a typo or a changed digit never opens another card
- Simple card settings: quantity, number format (digits only, or letters and digits without look-alikes), starts with, ends with, card number length, and password (none, any length from 1, the card number again, or one shared password for the batch)
- The preview table shows **exactly** the users that will be exported
- Export to a new timestamped folder:
  - **PDF** A4 voucher sheets, with an optional **QR auto-login** code on each voucher
  - **RSC** RouterOS script: every value is quoted and escaped, and existing usernames are skipped instead of aborting the import
  - **CSV**, UTF-8, opens correctly in Excel
- Export history and persistent settings (stored in `~/.netpilot`)
- **Router connection** (RouterOS 7.1+, REST API): loads the router's real hotspot profiles and servers. HTTPS uses certificate pinning. The password is only remembered if you tick **Remember password**, and then encrypted with your Windows account (DPAPI); optional automatic connection at start-up. Setup: [docs/ROUTER_SETUP.md](docs/ROUTER_SETUP.md)
- **Send to Router**: creates the generated users directly on the router. Existing names are replaced or skipped, every batch is tagged with a comment, and you can print vouchers for the created users or undo the batch.
- **Manage Users**: see every hotspot user with usage and online state, filter by batch, profile or state (expired, never used, ...), and enable, disable, disconnect, delete or reprint vouchers in bulk.
- **Voucher Design**: small modern card styles (up to 65 per A4 page) or **your own card picture** with the details dragged into place; network name, logo, colour, note and price per profile, with a live preview that matches the PDF. Vouchers can be printed in **Arabic** (joined letters, right-to-left) or English.
- **Receipt printing** on 58 or 80 mm thermal printers, one receipt per card, straight from Manage users or after sending a batch; choose the printer and paper width in **Voucher Design**, with a live receipt preview and a test print. A4 PDF printing stays available next to it.
- **Router discovery**: the connect dialog lists MikroTik routers on your network, like WinBox Neighbors.
- **Reports**: revenue per period, batch and profile, a revenue-per-day chart, and CSV export. A card counts as sold when it is first used.
- **WinBox-style main window**: side menu (Dashboard, Create cards, Hotspot, Reports, Voucher design, Export history, Settings, `Ctrl+1`…`Ctrl+7`), router connection in the top bar, and a status bar with the router's CPU, memory and uptime.
- **Hotspot page** with tabs like WinBox: users grouped by batch, active sessions, user profiles and servers.
- **English or Arabic interface** (right-to-left, Cairo font), **light, semi-dark, dark or system theme** and a choice of main colour: **Settings** page.

## Install (Windows)

Download `NetPilot-windows` from the latest run of **Windows installer** on the repository's
[Actions](https://github.com/0xibrR/netpilot/actions/workflows/installer.yml) page. It holds:

- `NetPilot-1.2.0.exe`: installs NetPilot with a desktop and Start-menu shortcut
- `NetPilot-1.2.0-windows.zip`: portable; unzip and run `NetPilot.exe`

Both include their own Java, so nothing else is needed. See [docs/INSTALLER.md](docs/INSTALLER.md).

## Requirements (to build from source)

- JDK 25 or newer
- No Maven installation needed: the Maven Wrapper (`mvnw`) is included

## Run

```bash
./mvnw javafx:run
```

On Windows (cmd / PowerShell):

```bash
mvnw.cmd javafx:run
```

## Test and quality checks

```bash
./mvnw verify
```

This runs the unit tests and checks formatting, coverage (≥ 80%) and SpotBugs. Format the code with `./mvnw spotless:apply`.
See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for the workflow and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design.

## Import users to MikroTik

1. Export users from NetPilot.
2. In WinBox, open **Files** and upload the generated `.rsc` file.
3. Open **New Terminal** and run:

```text
/import file-name=2M_20users.rsc
```

Usernames that already exist on the router are skipped and logged as warnings.

### QR auto-login

Set **QR login URL** to your hotspot login page, for example `http://10.10.0.1/login`. Each QR code opens
`<login-url>?username=...&password=...`, which the default MikroTik hotspot login page accepts.
Leave the field blank to print vouchers without QR codes.

## Project structure

```text
src/main/java/org/netpilot/
├── Launcher.java          entry point
├── NetPilotApp.java       JavaFX application
├── AppContext.java        dependency wiring
├── model/                 records and enums (no JavaFX)
├── service/               generation and export logic (no JavaFX)
├── repository/            settings and history storage
└── controller/            JavaFX controllers
src/main/resources/org/netpilot/
├── view/                  FXML views
└── css/                   styles
```

## Author

Ibrahim Ramadan, [github.com/0xibrR](https://github.com/0xibrR)

## Third-party assets

- **Cairo** font, © 2009 The Cairo Project Authors, licensed under the [SIL Open Font License 1.1](src/main/resources/org/netpilot/fonts/Cairo-OFL.txt). It is embedded in voucher PDFs.
