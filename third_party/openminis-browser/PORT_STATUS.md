# Local browser port status

## Integrated at source level

- Browser opening now presents the imported BrowserSheet as a modal over the existing
  app screen, instead of navigating into a full-screen shell. Close, scrim and
  back dismissal release browser ownership without popping the underlying chat.
- Preserved tab bar (up to 3 tabs), new/close/select tab, address input, navigation,
  history/search, download list/progress/cancel/open, UA/custom UA, viewport presets,
  cookie/data controls, and idle-tab timeout settings.
- Browser theme now uses upstream OpenMinis Theme.kt and ChatColors.kt: original
  accent/surface palettes, 12/20/24/28 dp shapes, typography, semantic header colors,
  compact drag handle and 90% sheet. Only light/dark selection follows Eta.
  Settings/history/downloads and their confirmation dialogs inherit the same theme.
  Browser-only resources are namespaced with English, Simplified and Traditional Chinese.
- AgentBrowserSession now owns the same tab pool as the UI and keeps the original
  execute/snapshot/preview/cancellation interfaces for existing call sites.
- UI acquisition cancels and waits for the active tool; visible UI blocks new tools.
  UI release restores off-screen bounds, with ownership identity to avoid rapid
  close/reopen races.
- browser_use adds new_tab/close_tab/list_tabs/tab_id; old actions, paging, coordinate
  inputs, submit, timeout aliases and read_image=false are retained.
- Eta guest paths, cookie env-file offload, output limits, bounded screenshot allocation,
  download limits and a narrow cache FileProvider path replace upstream-specific wiring.
- Removed implicit Google-login app launches; no Antigravity model/login code imported.
- Antigravity removal is included in the branch history.

## Checks actually performed

- Parsed the three locale XML sets; no duplicate string resource names.
- Resolved all 54 imported UI string names against default resources.
- Compared all 26 browser tool actions with the ported action enum.
- Checked removed old-browser method call sites and stale upstream sandbox imports.
- git diff --check passes.
- Baseline 753a243 passed 1398 tests and Release compilation in Actions run 35264341262.

## Not verified yet

- Follow-up UI, history, accessibility, haptics and diagnostic changes still require
  a fresh main-branch CI run. Baseline success does not validate these changes.
- Need device tests: mount/unmount/rotation, rapid close/reopen, tab close and renderer
  death, keyboard, dark theme, downloads and redirects, cancellation, local-file pages,
  cookies, per-tab tool targeting and long-running async page scripts.
- Android per-domain cookie deletion is best-effort for root-path cookies; only the
  confirmed 'clear all' action clears the full shared store/site data.
- This is an integrated source draft, NOT a claim of production-ready full equivalence.

## Build/publication authorization

Target branch: main. The owner explicitly instructed moving all pending changes to
main and deleting both local and remote private/openminis-browser branches.
The repository owner explicitly authorized public push and GitHub Actions compilation
after being informed the origin repository is public. Preserve upstream attribution;
this authorization is not a declaration of license compatibility. Do not create a
Release or install automatically. See subsequent CI results for actual validation.

## UI design alignment after build 753a243

- Original modal presentation and independent upstream theme restored locally.
- Long titles truncate while keeping symmetric header slots and close action visible.
- Added palette/shape regression tests; source/locale/reference checks only so far.
- This UI follow-up has NOT been compiled or visually verified. The earlier CI pass
  applies to 753a243, not these later changes.
