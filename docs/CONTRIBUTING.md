# Development Workflow

## Every phase or feature

1. **Design note first.** What are we building? Which classes change? What are the security risks?
2. **Branch:** `feature/<short-name>` from `master`. `master` must always build.
3. **Implement with tests.** New logic gets its unit tests in the same change.
4. **Run the gates locally:**
   ```bash
   ./mvnw spotless:apply verify
   ```
5. **Try it in the app:** `./mvnw javafx:run`
6. **Pull request → CI green → merge.** Tag releases as `vMAJOR.MINOR.PATCH`.

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

## Useful commands

| Command | Purpose |
|---|---|
| `./mvnw javafx:run` | Run the app |
| `./mvnw test` | Unit tests only |
| `./mvnw verify` | All quality gates |
| `./mvnw spotless:apply` | Format the code |
| `./mvnw spotbugs:gui` | Browse SpotBugs findings |
