# 🍝 Spaghetti Code

An IntelliJ plugin that untangles giant single-file code, the kind AI tools love to generate.
Right-click a 3,000-line `index.html`, choose **Untangle Spaghetti**, pick one of three proposed
layouts, and get a clean folder structure. Nothing is lost, and nothing changes behavior.

It also works, more narrowly, on a standalone `.css` or `.js` file that has grown too large on its
own — see [Standalone .css and .js files](#standalone-css-and-js-files) for exactly what it does and,
just as importantly, what it deliberately declines to touch and why.

Built for the 42 Abu Dhabi × JetBrains "Help the Developer" hackathon, September 22–23, 2026.

---

## Build, run, test

You need IntelliJ IDEA 2026.2 (it includes the Java 25 runtime Gradle uses).

**From IntelliJ (easiest):** open this folder as a project, then use the run configurations in the top-right dropdown:

| Run configuration | What it does |
|---|---|
| **Run Plugin** | Starts a second "sandbox" IntelliJ with the plugin installed. Open the `playground/` folder in it and right-click `index.html`. |
| **Run Tests** | Runs the automated tests in `src/test`. |

**From a terminal** (PowerShell, from this folder):

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr"
.\gradlew.bat test           # run tests
.\gradlew.bat runIdeLocal    # launch sandbox IDE (uses your installed IntelliJ)
.\gradlew.bat runIdeLocal "-PideProject=<folder>"   # same, opened straight on a folder (copy playground/ first so you can reset it)
.\gradlew.bat buildPlugin    # produce build/distributions/spaghetti-code-0.1.0.zip
```

The first build downloads about 1.5 GB (a developer build of IntelliJ for compiling and testing). After that, builds take seconds.

To reset the playground after untangling it: `git checkout -- playground` (and delete any new files).

### Prove that untangling changed nothing

`DemoExportTest` runs the real planner on the three Nimbus pages and writes `build/demo/before/` and `build/demo/after/`.
`tools/verify` then loads both versions in a browser and compares them:

```powershell
.\gradlew.bat test                    # also regenerates build/demo
node tools/verify/serve.mjs           # then open http://localhost:8765/compare.html?page=index  (or about, changelog)
```

It checks that all CSS rules are identical and in the same order, every global still exists, every `onclick="..."` still
resolves, and every rendered element has the same computed style and position. To see it fail, break a copy of an `after/`
page by hand (delete a `<script>` tag, add a CSS rule) and reload with `?page=<that page>`.

---

## How it works

```
Right-click file (or Tools menu) -> Untangle Spaghetti
   │
   ├─ 1. DETECT    LightPlanner looks for <style>/<script> blocks worth extracting (size, type,
   │               no <script src>, no @import). Finds nothing → "looks clean", nothing changes.
   │               By feature / By type only ever subdivide what Light already found, so this one
   │               check is enough to decide "is this file worth untangling at all".
   │
   ├─ 2. PLAN      All three strategies are computed up front:
   │                 🥄 Light       whole blocks merged into one CSS file, one file per script
   │                 🍝 By feature  blocks also cut at their own comments (/* ---- hero ---- */)
   │                                into one file per named section — see CommentSections
   │                 🗂️ By type     same cut points as By feature, filed by kind instead:
   │                                variables.css / responsive.css / components/*.css,
   │                                state.js / handlers/*.js
   │
   ├─ 3. PICK      StrategyPickerDialog shows file counts for all three; user picks one.
   │
   ├─ 4. PREVIEW   PreviewDialog lists every resulting file, ticked by default. Files cut from the
   │               same block share a groupId and are ticked/unticked together — see
   │               PlannedFile.groupId for why partial selection would 404 a link or duplicate code.
   │
   └─ 5. APPLY     PlanApplier writes the ticked files and rewrites the HTML in one WriteCommandAction,
                    so one Ctrl+Z undoes everything, files included.
```

**Design rules**

1. **Code is cut, never rewritten.** Every byte in an extracted file came from the original block,
   at the same relative position. Filenames come from the code's own comments, not AI, and not a rewrite.
2. **Don't fix what isn't broken.** Blocks under ~15 lines (`LightPlanner.MIN_LINES` /
   `CommentSections.MIN_LINES`) stay where they are, or get folded into a bigger neighboring section.
3. **Behavior must not change.** CSS keeps its cascade order — a run of `<style>` blocks only merges when
   there is *nothing* between them: no other stylesheet, no skipped `<style>`, and no `<script>` either
   (merging across a script wouldn't change the final cascade, but the script could read computed style or
   layout at that exact point in parsing, and merging would change what it sees then). Scripts keep their
   load order and stay classic `<script>` tags (not `type="module"`, which `file://` refuses to load), and
   anything with an unusual attribute, `@import`, or non-JS `type` is left inline.
4. **The user is always in control.** Nothing is written until they pick a strategy, review the file
   list, and press Untangle. One Ctrl+Z undoes everything, extracted files included.

See [`tools/verify`](#prove-that-untangling-changed-nothing) for how "nothing changes" is actually checked, not just asserted.

---

## Standalone .css and .js files

Right-click any `.html`, `.css` or `.js` file — the action detects which it is and runs the matching
flow. The HTML case is everything above. The other two only work because each language happens to have
a way to reference a sibling file that nothing else needs to know about:

**`.css`** — split at its own top-level comments (`CssFileSplitter`, same `CommentSections` cutter as
By feature), then the original file is rewritten to a short list of `@import url("...");` statements,
in order. `@import` is standard CSS, always loads before other rules (trivially true here, since
imports are now the *only* thing in the file), and loads in the order written — so any `<link
href="app.css">` anywhere keeps working, completely unmodified, because `app.css` still exists. Nothing
outside this one file has to change.

**`.js`** — only in the one case that's actually safe: an ES module entry point that nothing else
imports symbols *from*. `JsFileSplitter.checkEligibility` requires both:
- at least one top-level `import`, meaning whatever loads it already uses `<script type="module">` —
  the plugin isn't deciding to change how it's loaded, that was already decided;
- **no** top-level `export`. If the file exported anything, some other file might do `import { thing }
  from "./this.js"`, and splitting it apart re-assembled with `export *` can silently *drop* a name if
  two pieces happen to export the same identifier — exactly the kind of silent behavior change this
  plugin exists to prevent, so it declines rather than risk it.

A file that fails either check gets a notification explaining exactly why, never a silent no-op and
never a guess: a classic (non-module) script could be loaded by any number of pages the plugin has no
way to check, and a module with exports could be depended on the same way. When it *is* eligible, the
original file is rewritten to side-effect-only `import "./app/section.js";` statements, in the same
order — this re-runs each piece's top-level code exactly once, in its original relative order, without
re-exporting anything (there is nothing to re-export; the eligibility check already ruled that out).

Both, like the HTML strategies, write every file from one block as a single tick/untick unit in the
preview (`groupId`) and apply as one `WriteCommandAction` — one Ctrl+Z removes the split files and
restores the original in one step.

---

## AI summary: "where do I look to change something?"

After a successful untangle, the notification (and the persistent version of it under the bell icon)
carries a **Summarize with AI** link. It is entirely optional, additive, and never runs on its own:

- The untangle itself never touches AI. Code is cut and moved by the plugin's own parser only — this is
  purely a description of files that already exist, generated after the fact.
- Clicking it is the *only* time this plugin ever touches the network or asks for an API key. Never
  clicking it, the plugin works forever offline. The key is checked in this order: the `OPENAI_API_KEY`
  environment variable, then IntelliJ's encrypted `PasswordSafe` (never a plain file, never logged) —
  and if neither exists, a one-time dialog asks for it and saves it for next time.
- It runs as a background task (the UI never freezes on the network call) and writes `MANIFEST.md` next
  to the split files: one line per file, in a table, saying what it's responsible for. A failure — no
  internet, a bad key, a rate limit, a malformed reply — shows a clear reason and changes nothing; the
  untangle that already happened is completely unaffected either way.

```
src/main/kotlin/dev/spaghetti/ai/
├── SummaryPrompt.kt      builds the request text, parses the model's JSON reply -- pure, no network
├── ManifestWriter.kt     renders MANIFEST.md -- pure text
├── SummarizeFlow.kt      the above two wired together behind one injectable `complete` function,
│                         so it's fully testable without ever calling the real API
├── OpenAiClient.kt       the only class that actually makes an HTTP call (api.openai.com, gpt-4o-mini)
├── ApiKeyStore.kt        env var, then PasswordSafe
├── ApiKeyDialog.kt       the one-time "enter your key" prompt
└── SummarizeCommand.kt   wires it all to a real project: background task, VFS write, notification
```

---

## Code map

```
src/main/kotlin/dev/spaghetti/
├── actions/
│   ├── UntangleAction.kt    the right-click / Tools menu entry — start here
│   └── UntangleFlow.kt      what happens after the click, minus the dialogs (so tests can drive it headlessly)
├── untangle/
│   └── InlineBlockScanner.kt   finds every inline <style>/<script> block in an HTML file
├── plan/
│   ├── LightPlanner.kt      🥄 strategy; also owns the shared "is this block extractable" rules
│   ├── FeaturePlanner.kt    🍝 strategy: cuts each block at its own comments, names files from them
│   ├── TypePlanner.kt       🗂️ strategy: same cuts, classified into variables/responsive/components/state/handlers
│   ├── CommentSections.kt   the comment-boundary cutter both By feature and By type share
│   ├── BlockPlanner.kt      turns one block's sections into grouped PlannedFiles + HTML edits
│   ├── CssFileSplitter.kt   standalone .css file -> sections + @import rewrite
│   ├── JsFileSplitter.kt    standalone .js file -> eligibility check, then sections + import rewrite
│   ├── PlanPaths.kt         picks collision-free file paths
│   ├── SplitPlan.kt         PlannedFile / SplitPlan / HtmlEdit — the language-neutral plan model
│   └── PlanApplier.kt       writes the plan as one undoable WriteCommandAction
└── ui/
    ├── StrategyPickerDialog.kt   pick Light / By feature / By type, with file counts
    └── PreviewDialog.kt         tick/untick files (grouped files move together), then Apply

src/main/resources/META-INF/plugin.xml   registers the action with IntelliJ
src/test/kotlin/                         automated tests (121, see `gradlew test`), incl. edge cases,
                                          10 distinct real-world-pattern pages (EdgeCaseTest,
                                          TenPagesRobustnessTest), the standalone .css/.js splitters
                                          (CssFileSplitterTest, JsFileSplitterTest) and the AI summary
                                          feature (dev/spaghetti/ai/ -- all pure logic, no real API calls)
src/test/testData/samples/               messy input files used by tests, incl. the Nimbus fixture
tools/verify/                            before/after browser check — see below
playground/                              a messy site to try the plugin on by hand
```

## The playground site

`playground/` is **Nimbus**, a fictional SaaS site built the way AI tools generate them: it looks polished, and the code is tangled.

| File | Lines | What makes it spaghetti |
|---|---|---|
| `index.html` | ~4,300 | 5 `<style>` blocks scattered through the page, 4 inline scripts (one mid-page), dozens of `onclick="..."` handlers calling global functions, inline `style="..."` attributes |
| `changelog.html` | ~1,400 | Copy-pasted nav, footer, colour tokens, buttons, toasts, theme and menu scripts from `index.html` |
| `about.html` | ~1,500 | The same copies, but **drifted**: `--violet` is `#8b7cff` instead of `#8f7dff`, and toasts last 2.5 s instead of 3.2 s |

Things the plugin must leave alone: the JSON-LD `<script type="application/ld+json">`, the external confetti `<script src>`, and the Google Fonts `<link>`.

The cross-file copies are for a stretch feature: detect code duplicated across files and offer to extract it into shared files (`shared/tokens.css`, `shared/nav.js`). The drifted copy in `about.html` is the interesting case: the plugin should notice the difference and ask which version to keep instead of silently picking one.

## Useful docs

- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/welcome.html
- PSI (how IntelliJ represents parsed code): https://plugins.jetbrains.com/docs/intellij/psi.html
- Actions: https://plugins.jetbrains.com/docs/intellij/basic-action-system.html
- Dialogs: https://plugins.jetbrains.com/docs/intellij/dialog-wrapper.html
- Write actions and undo: https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html
