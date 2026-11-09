# Backup and restore

**Settings → Backup.**

## What is in a backup

| File | Holds |
|---|---|
| `settings.properties` | all settings, router address and trusted certificates, card design |
| `voucher-logo.*`, `voucher-background.*` | the logo and your own card picture |
| `usage.csv` | first use of each card (Earnings and sales) |
| `history.csv` | print history |
| `resellers.csv`, `reseller-ledger.csv` | points of sale and their ledger |

Never included: the remembered router password (`credentials`, encrypted for this Windows account
and unreadable anywhere else) and the logs. After restoring on a new computer, enter the router
password once when connecting.

## Format

A zip with `manifest.txt`: format, version, creation time, NetPilot version, and for every file its
size and SHA-256. With a password the whole zip is encrypted with AES-256-GCM; the key comes from
the password with PBKDF2-HMAC-SHA256 (310,000 rounds, random salt). Without the password neither the
file names nor the contents can be read, and any change to the file is detected.

## Safety rules

- **Atomic backups.** A backup is written to a temporary file in the target folder, read back and
  checked like a restore would check it, and only then renamed to its final name. On any failure
  the temporary file is deleted and nothing else is left.
- **Automatic backups** (once a day, last 14 kept) remove old copies only after a new copy was made
  and checked. A failed automatic backup is logged and tried again an hour later.
- **Checked before anything changes.** A restore refuses a file that is not a NetPilot backup, comes
  from a newer format, is missing a listed file, has a file the manifest does not list, or whose
  size or SHA-256 does not match, or the wrong password.
- **Newer data warning.** If the data on this computer has ledger lines or sales the backup does not
  have, the restore says how many would be lost and asks again.
- **The current data is saved first** to `.netpilot/backups/NetPilot-before-restore-<time>.zip`.
- **Transactional restore.** All files are written to `.restore-staging` first; then the current
  files are moved to `.restore-old` (with a journal) and the new ones moved in. If any step fails,
  every file is moved back. A restore cut short by a power cut is undone at the next start, before
  anything reads the data.
- **Pictures on another computer.** The logo and card picture paths in the settings are pointed at
  the new computer's data folder.
