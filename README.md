# mycourses-sync

A read-only, Git-backed local sync tool for McGill myCourses (Brightspace/D2L).

Pulls course files, announcements, and assignment metadata into a local folder, then
commits every change so you can `diff` between runs and see exactly what changed.

---

## What it does

- Downloads course content (files, announcements, assignments) from myCourses
- Organizes everything into `~/mcgill-courses/{COURSE_CODE}/`
- Commits every sync run to a local Git repo — one commit per pull
- Lets you run `mycourses diff` / `mycourses status` to see what changed

## What it does NOT do

- Never writes anything back to myCourses
- Never submits assignments
- Never bypasses McGill login or scrapes passwords
- No browser automation

---

## Requirements

- Java 17+
- Maven 3.9+
- An OAuth2 `client_id` registered with McGill Brightspace admin (see below)

---

## Build

```bash
cd mycourses-sync
mvn package -q
```

> **No `mvn` on PATH?** IntelliJ ships its own Maven. On this machine:
> ```bash
> ~/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven/lib/maven3/bin/mvn package -q -DskipTests
> ```
> Or install Maven: `sudo pacman -S maven` (Arch) / `sudo apt install maven` (Ubuntu)

This produces `target/mycourses-sync-1.0-SNAPSHOT.jar` (fat JAR, all deps bundled).

**Add to PATH (optional):**
```bash
cp mycourses ~/.local/bin/mycourses   # or any directory on your $PATH
chmod +x ~/.local/bin/mycourses
```

Or run directly:
```bash
java -jar target/mycourses-sync-1.0-SNAPSHOT.jar <command>
```

---

## Setup

### Step 1 — Obtain an OAuth2 client_id

This is the hardest step, and the most important constraint.

**Option A (preferred): Request API access from McGill IT**

Email `it-servicedesk@mcgill.ca` and ask for an OAuth2 client_id for the Brightspace LP
API. Provide:
- Redirect URI: `http://localhost:9004/callback`
- Scopes needed: `core:*:* enrollment:Course:Read content:*:* dropbox:* news:*:*`
- Purpose: personal read-only local backup tool

McGill may also have a self-service developer portal at:
`https://mycourses2.mcgill.ca/d2l/home` → Admin Tools → OAuth 2.0

**Option B (fallback): Manual export + local Git**

If API access is unavailable, use McGill's built-in export features:

1. In myCourses, go to each course → Content → Export/Download
2. Place the downloaded files into `~/mcgill-courses/{COURSE_CODE}/`
3. Run `git -C ~/mcgill-courses add . && git -C ~/mcgill-courses commit -m "Manual sync"`
4. Use `git -C ~/mcgill-courses diff HEAD~1` to see what changed

This gives you the same local Git history without API access.

### Step 2 — Initialize

```bash
mycourses init --client-id YOUR_CLIENT_ID
```

This creates:
- `~/.mycourses/config.json` — tool config (tenant URL, client_id, sync dir)
- `~/mcgill-courses/` — local course sync directory
- `~/mcgill-courses/.git` — local Git repo

Options:
```
--dir <path>       Override the local sync directory (default: ~/mcgill-courses)
--tenant <url>     Override the Brightspace URL (default: https://mycourses2.mcgill.ca)
--client-id <id>   OAuth2 client_id
```

### Step 3 — Login

```bash
mycourses login
```

Opens your browser to the McGill myCourses login page. After you authenticate,
the OAuth callback lands at `localhost:9004/callback` and the tool stores your
tokens at `~/.mycourses/tokens.json` (permissions: `rw-------`).

Tokens are refreshed automatically on subsequent pulls.

### Step 4 — Pull

```bash
mycourses pull            # sync all active courses
mycourses pull COMP273    # sync a single course by course code
```

For each course the tool:
1. Creates the folder structure
2. Downloads all files from the content TOC
3. Writes `announcements.md` from the News API
4. Writes per-assignment JSON from the Dropbox API
5. Commits all changes as `Sync myCourses - YYYY-MM-DD`

### Step 5 — View changes

```bash
mycourses status   # Git status + recent commit log
mycourses diff     # Unified diff of uncommitted changes
```

Or use standard Git directly on the sync directory:
```bash
git -C ~/mcgill-courses log --oneline
git -C ~/mcgill-courses diff HEAD~1
git -C ~/mcgill-courses show HEAD:COMP273/announcements.md
```

---

## Local folder structure

```
~/mcgill-courses/
  .git/
  COMP273/
    course-info.json      ← org unit metadata
    announcements.md      ← all announcements as Markdown
    lectures/             ← files from modules named "lecture*"
    assignments/
      Assignment1.json    ← metadata: name, due date, points (no submissions)
      Assignment2.json
    content/
      toc.json            ← raw API content tree
      Syllabus.pdf
      ...
  MATH240/
    ...
```

---

## Config file

`~/.mycourses/config.json`:
```json
{
  "tenantUrl": "https://mycourses2.mcgill.ca",
  "clientId": "your-client-id-here",
  "baseDir": "/home/you/mcgill-courses",
  "lpVersion": "1.28",
  "leVersion": "1.67"
}
```

The API version strings (`lpVersion`, `leVersion`) may need to be updated if McGill
upgrades their Brightspace instance. Check current versions at:
`https://mycourses2.mcgill.ca/d2l/api/lp/versions/`

---

## Limitations

| Limitation | Reason |
|---|---|
| Requires OAuth2 client_id from McGill IT | Brightspace uses OAuth; self-service registration may not be available |
| Only reads what the API exposes | Some content types (e.g. HTML pages, embedded videos) may not be downloadable as files |
| Binary files don't appear in `git diff` | Git diffs text; PDFs/slides show up in `git status` as modified but diff output will be empty |
| No quiz/grade data | These require additional scopes and raise privacy concerns; intentionally excluded |
| Refresh token may expire | If your refresh token expires (typically 30-90 days), re-run `mycourses login` |

---

## Project structure

```
src/main/java/ca/mcgill/mycourses/
  Main.java                        — CLI entry point (Picocli)
  cli/
    InitCommand.java
    LoginCommand.java
    PullCommand.java
    StatusCommand.java
    DiffCommand.java
  model/
    AppConfig.java                 — config.json shape
    TokenInfo.java                 — tokens.json shape
    Course.java
    Announcement.java
    Assignment.java
  service/
    ConfigService.java             — read/write ~/.mycourses/{config,tokens}.json
    AuthService.java               — OAuth2 authorization code flow
    BrightspaceClient.java         — read-only D2L Valence API client
    CourseSyncService.java         — orchestrates API → filesystem sync
    FileStorageService.java        — writes files + Markdown to disk
    GitService.java                — JGit: init, add, commit, status, diff, log
```

---

## Security notes

- Tokens are stored in `~/.mycourses/tokens.json` with `600` permissions (owner-only)
- The config directory `~/.mycourses/` has `700` permissions
- The OAuth redirect uses a random `state` parameter to prevent CSRF
- No credentials (username/password) are ever stored — only OAuth tokens
- The tool never makes any write requests to the Brightspace API
