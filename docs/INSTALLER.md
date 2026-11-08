# Windows installer

NetPilot is shipped in two forms. Both include a small Java runtime with JavaFX, so the computer
needs nothing else:

| File | Use |
|---|---|
| `NetPilot-<version>.exe` | Installer. Adds a desktop shortcut (optional), a Start-menu entry and an entry in Windows **Apps** to uninstall it. Installs for the current Windows user, so no administrator rights are needed. |
| `NetPilot-<version>-windows.zip` | Portable. Unzip anywhere (also a USB stick) and run `NetPilot\NetPilot.exe`. |

## Getting a build

Every push to `master` runs the **Windows installer** workflow
(`.github/workflows/installer.yml`). Open the run on the repository's Actions page and download
the `NetPilot-windows` artifact. GitHub keeps it for 30 days.

## Building on your own computer

```bash
./mvnw clean -Pinstaller package
```

The result is in `target/installer/`:

- `NetPilot-<version>-windows.zip`, always
- `image/NetPilot/NetPilot.exe`, the same app, ready to run without unzipping

The `.exe` installer also needs the [WiX Toolset](https://wixtoolset.org/) (v3, or v4/v5 with the
`WixToolset.Util.wixext` and `WixToolset.UI.wixext` extensions). With it installed:

```bash
./mvnw clean -Pinstaller -Dinstaller.exe=true package
```

## How it is built

1. `maven-dependency-plugin` copies the libraries to `target/installer/mods` and the Windows JavaFX
   modules to `target/installer/fx`.
2. `jlink` builds `target/installer/runtime`: only the JDK modules NetPilot uses (found with
   `jdeps`), plus JavaFX, English and Arabic locale data.
3. `jpackage` builds the app folder (`NetPilot.exe`, the app's modules, the runtime) and, when
   asked, the `.exe` installer from that same folder.

Some libraries (ZXing, OpenPDF, JNA) are not explicit Java modules, so they cannot go into the
`jlink` runtime; `jpackage` keeps them next to the app and starts NetPilot on the module path.

## Things to know

- **Your data is kept.** Settings, card design, sales records, print history and a remembered
  router password live in `%USERPROFILE%\.netpilot`, not in the program folder. Installing a newer
  version, or uninstalling, does not touch them.
- **Updates.** A newer installer replaces the installed version (same upgrade id in `pom.xml`;
  never change `installer.upgrade.uuid`). Raise `installer.version` together with
  `NetPilotApp.VERSION` for each release.
- **"Windows protected your PC".** The installer is not signed with a paid code-signing
  certificate, so SmartScreen may warn the first time. Choose **More info → Run anyway**.
- **Windows only for now.** A macOS package can be added later the same way (`jpackage --type dmg`).
