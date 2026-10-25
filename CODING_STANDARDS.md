# Coding Standards and Contribution Workflow

These standards apply to the whole repository: the Python server (`WalkingMeditationServer-main`) and the Android app (`WalkingMeditationMobileApp-main`). They describe the conventions the code already follows, plus the checks that keep it that way. Section 6 lists exactly what is enforced automatically and what is enforced by review.

## 1. Python server

| Topic | Standard |
|---|---|
| Version | Python 3.13 only. |
| Style | 4-space indentation, `snake_case` for functions and variables, `PascalCase` for classes, `UPPER_CASE` for constants. Aim for lines of 100 characters or fewer. |
| Layering | `route/*.py` routers handle HTTP only; `route/services/` holds business logic; `route/clients/` wraps external APIs (Google, OpenAI); `database/` holds models and migrations. Do not call external APIs from a router. |
| Types | Annotate function parameters and return types. Use Pydantic models (`route/models.py`) for every request and response body. |
| Async | Use `async`/`await` for database and network calls. Do not block the event loop. |
| Auth | Protect endpoints with the dependencies in `route/dependencies.py` (`CurrentUserDependency`, verified researcher, study owner, admin). Never check roles inside the handler body instead. |
| Errors | Raise `HTTPException` with the correct status code and a clear `detail` message. Use 400 for bad input, 401 unauthenticated, 403 not permitted, 404 not found, 409 conflict, 422 validation. |
| Logging | Use the `logging` module. No `print()` in server code (command-line tools such as `scripts/` and `questionnaire/questionnaire.py` are exempt). Never log passwords, tokens or API keys (the development password-reset token log is a known exception to remove before production). |
| Secrets | Secrets and URLs come from environment variables (`.env`, never committed). Add any new variable to `.env.example` and the setup guide. |
| Database | Change the schema only through an Alembic migration, reviewed before merging. Run `alembic heads` before merging: there must be exactly one head. Create enum types explicitly (see Migration gotchas). |
| Imports | No wildcard imports (`from x import *`). Remove unused imports. |
| Comments | Explain why, not what. Remove commented-out code. Keep TODOs linked to a ticket. |

## 2. Android app (Java)

| Topic | Standard |
|---|---|
| Style | 4-space indentation, no tabs. `camelCase` methods and fields, `PascalCase` classes, `UPPER_CASE` constants. |
| Naming | One screen per class named `XxxActivity` with layout `activity_xxx.xml`. Adapters end in `Adapter`, API clients in `ApiClient` (in `utils`). |
| View ids | `camelCase` with a type prefix for buttons, for example `btnLogin`, `btnSignUp`; text fields end in `Field`, for example `emailField`. |
| Strings and colours | User-visible text belongs in `res/values/strings.xml` and colours in `colors.xml` or the theme. Some older screens still hard-code text; fix these when touching the screen. |
| Networking | All server calls go through the API clients in `utils`, never directly from an Activity. Clients attach the bearer token and handle refresh. |
| Config | No keys or URLs in source. Use Gradle properties (`MAPS_API_KEY`, `SERVER_BASE_URL`, `SERVER_ACCESS_TOKEN`). |
| Permissions | Request permissions where they are needed, and declare only what is used. |
| Manifest | Register every new Activity in `AndroidManifest.xml`; an unregistered Activity crashes at runtime. |
| Lint | Android Lint (`./gradlew lintDebug`) should report no new errors. |

## 3. Testing

- Every new endpoint or behaviour change ships with tests. Server tests live in `WalkingMeditationServer-main/tests/test_*.py` and run with `python -m pytest`. They use fakes, so no live database is needed.
- Android unit tests live in `app/src/test` and run with `./gradlew test`.
- A bug fix includes a test that fails without the fix.
- Both suites must pass in CI before merging.

## 4. Git workflow

- **Branches:** work on a branch named `feature/<name>` or `fix/<name>`. Never commit directly to `main`.
- **Commits:** a short imperative summary (for example "Add questionnaire revoke endpoint"), with detail in the body when needed. One logical change per commit.
- **Pull requests:** one PR per feature or fix. Link the user story (for example US6 or RS3).
- **Review:** every PR needs at least one approval from a teammate who did not write it. The author does not merge their own unreviewed work.
- **Merging:** CI must be green and the branch up to date with `main`. Prefer squash merge to keep history readable.

## 5. Documentation

When you change behaviour, update the matching page in the same PR: the API reference for endpoints, the database page for schema changes (and the data model diagram), the app guide for screen flows, and `.env.example` for configuration.

## 6. How the standards are checked

| Standard | How it is checked | Type |
|---|---|---|
| Server tests pass | `python -m pytest` in CI (workflow "Server Unit Tests") | Automatic, blocks merge once required in branch protection |
| Android unit tests pass | `./gradlew test` in CI (workflow "Android Tests") | Automatic, blocks merge once required in branch protection |
| No unused imports, undefined names, redefinitions, syntax errors (server) | `ruff check .` in CI (`ruff.toml`) | Automatic, blocks merge once required in branch protection |
| Android Lint | `./gradlew lintDebug` | **Planned:** not yet run in CI |
| One reviewer approval, no direct pushes to `main`, CI required | GitHub branch protection on `main` | Automatic once enabled in repository settings |
| Naming, layering, auth dependencies, error handling, docs updated, tests added | PR template checklist and reviewer | By review |

**Branch protection settings** (repository Settings → Branches → Add rule for `main`): require a pull request before merging, require 1 approval, require the status checks "Server Unit Tests" (includes ruff) and "Android Tests" to pass, and block direct pushes.

## Known gaps against these standards

- Android Lint is not yet run in CI (`./gradlew lintDebug`).