# Development Workflow

## Every phase or feature

All work happens directly on `master`. There are no feature branches or pull requests.

1. **Design note first.** What are we building? Which classes change? What are the security risks?
2. **Implement with tests.** New logic gets its unit tests in the same change.
3. **Run the gates locally before every commit.** Never commit when this fails:
   ```bash
   ./mvnw spotless:apply verify
   ```
4. **Try it in the app:** `./mvnw javafx:run`
5. **Commit and push to `master`.** GitHub Actions re-runs the gates on Linux and Windows. If it fails, fix it right away with a follow-up commit.
6. Tag releases as `vMAJOR.MINOR.PATCH`.

## Definition of Done

- [ ] `mvnw verify` passes (format, tests, coverage ≥ 80%, SpotBugs)
- [ ] New logic has unit tests, including edge cases and invalid input
- [ ] No secrets in code, logs or error messages; all user input is validated
- [ ] Every error reaches the user as a clear message, and none is swallowed silently
- [ ] `model` / `service` / `repository` still have no `javafx` imports
- [ ] Clear names. Comments explain *why*, not *what*
- [ ] `CHANGELOG.md` updated, and `README.md` too if behaviour changed
- [ ] Tested manually in the running app

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `build:`, `ci:`, `chore:`.

```text
feat: connect to router through RouterOS REST API
fix: keep incomplete last row in voucher PDF
```

## Adding or changing texts

Never write user-visible text directly in Java or FXML:

1. Add the key to **both** `src/main/resources/org/netpilot/i18n/messages_en.properties` and `messages_ar.properties`, in the matching section.
2. Use it as `text="%my.key"` in FXML, or `Messages.get("my.key", value)` in Java (`{0}`, `{1}` are the values; write `''` for an apostrophe in a text that has values).
3. Enum labels use `prefix.CONSTANT_NAME` keys, e.g. `theme.DARK`.
4. Run `./mvnw test`: `MessagesTest` reports missing keys, different placeholders and untranslated view text.

Log messages stay in English and are not translated.

## Useful commands

| Command | Purpose |
|---|---|
| `./mvnw javafx:run` | Run the app |
| `./mvnw test` | Unit tests only |
| `./mvnw verify` | All quality gates |
| `./mvnw spotless:apply` | Format the code |
| `./mvnw spotbugs:gui` | Browse SpotBugs findings |
