# NetPilot

[![Build](https://github.com/0xibrR/netpilot/actions/workflows/build.yml/badge.svg)](https://github.com/0xibrR/netpilot/actions/workflows/build.yml)

Network management toolkit. It currently generates MikroTik Hotspot users, printable QR vouchers, and RouterOS import scripts.

Built with **Java 25**, **JavaFX 27**, and **Maven**, using an MVC architecture.

## Features

- Generates hotspot users with **guaranteed-unique** usernames
- Username modes: random (letters + digits), random digits, sequential (`user0001`), profile based (`2M0001`)
- Password modes: random digits, random letters + digits, same as username, prefix + username, sequential, with a warning for weak modes
- The preview table shows **exactly** the users that will be exported
- Export to a new timestamped folder:
  - **PDF** A4 voucher sheets, with an optional **QR auto-login** code on each voucher
  - **RSC** RouterOS script: every value is quoted and escaped, and existing usernames are skipped instead of aborting the import
  - **CSV**, UTF-8, opens correctly in Excel
- Export history and persistent settings (stored in `~/.netpilot`)
- **Router connection** (RouterOS 7.1+, REST API): loads the router's real hotspot profiles and servers. HTTPS uses certificate pinning, and the password is never saved. Setup: [docs/ROUTER_SETUP.md](docs/ROUTER_SETUP.md)
- **Send to Router**: creates the generated users directly on the router. Existing names are replaced or skipped, every batch is tagged with a comment, and you can print vouchers for the created users or undo the batch.
- **Manage Users**: see every hotspot user with usage and online state, filter by batch, profile or state (expired, never used, ...), and enable, disable, disconnect, delete or reprint vouchers in bulk.

## Requirements

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
