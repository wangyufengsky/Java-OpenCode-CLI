# AgentBridge Task Console Figma Contract

This contract uses Figma file `7SMWQrlbLmB6yZ1kUbJ26o`. Screenshots are checked in so visual review is reproducible. The Figma Desktop `get_design_context` bridge was selection-scoped during collection: it returned full context only for the selected typography frame `6:110`; the remaining requested node IDs returned `desktop selection required`. Their names, hierarchy and variant property names were obtained through the permitted structural metadata call; visual values below are from the corresponding Figma screenshots.

## Foundations

| Token | Figma evidence | Implementation |
| --- | --- | --- |
| Desktop reference | App shell variants: 1440 × 1024 | 224px sidebar + 1216px body |
| Canvas / surface / border | `#f5f7fb` / `#ffffff` / `#e1e7ef` | `--color-canvas`, `--color-surface`, `--color-border` |
| Primary / secondary text | `#315fe9` / `#607086` | `--color-primary`, `--color-muted` |
| Controls | 44px height | `--control-height: 44px` |
| Radius | 8 / 12 / 16 / full | `--radius-sm/md/lg`, pills use `999px` |
| Typography | Inter 32/40 bold; 24/32, 20/28 semibold; 16/24 and 14/20 regular; label 14/20 medium, 12/16 semibold; Roboto Mono 13/20 | `styles.css` foundation and existing type rules |

## Component and state mapping

| Figma node ID | Actual node name / variants | Screenshot evidence | Fragment / CSS mapping |
| --- | --- | --- | --- |
| `0:1` | 00 Cover | `docs/figma-baselines/components/0-1.png` (1440×900) | direction reference |
| `5:2` | 01 Getting Started | `docs/figma-baselines/components/5-2.png` (1440×900) | foundations reference |
| `5:3` | 02 Foundations / Color | `docs/figma-baselines/components/5-3.png` (1440×1100) | `@layer foundations` tokens |
| `5:4` | 03 Foundations / Typography | `docs/figma-baselines/components/5-4.png` (1440×1160) | typography contract; full selected context `6:110` |
| `5:5` | 04 Foundations / Spacing & Radius | `docs/figma-baselines/components/5-5.png` (1440×1080) | radius, spacing and desktop shell values |
| `61:2` | 19 Components / Navigation Item — State=Default, Hover, Active (192×40) | `docs/figma-baselines/components/61-2.png` (1360×480) | `navigationItem`, `.c-navigation-item` |
| `10:2` | 11 Components / Button — Style=Primary/Secondary/Danger × State=Default/Hover/Disabled (84×44) | `docs/figma-baselines/components/10-2.png` (1104×288) | `button`, `.c-button`; 44px control token |
| `14:2` | 12 Components / Status Badge — State=Queued, Running, Succeeded, Failed (62×24) | `docs/figma-baselines/components/14-2.png` (1120×516) | `statusBadge`, `.c-status-badge` |
| `23:2` | 13 Components / Input — State=Default, Focus, Error, Disabled (320×100) | `docs/figma-baselines/components/23-2.png` (2056×574) | existing semantic form controls and CSS foundations |
| `28:2` | 14 Components / Metric Card — Tone=Neutral, Primary, Success, Warning, Danger (240×136) | `docs/figma-baselines/components/28-2.png` (1968×544) | `metricCard`, `.c-metric-card` |
| `40:2` | Components / Table Row — State=Default, Hover, Selected, Attention | `docs/figma-baselines/components/40-2.png` (1200×1152) | `tableRow`, `.c-table-row` |
| `44:2` | 17 Components / Alert — Tone=Info, Success, Warning, Danger | `docs/figma-baselines/components/44-2.png` (1400×828) | `alert`, `.c-alert` |
| `54:2` | 18 Components / Drawer Shell — Mode=Create/Edit × State=Default/Loading | `docs/figma-baselines/components/54-2.png` (2200×2160) | `.c-drawer`, schedules dialog contract |
| `73:4` | 21 Components / App Shell — Active=Dashboard, NewRun, History, Schedules, None | `docs/figma-baselines/components/73-4.png` (2976×4256) | `.app-shell`, `@layer shell` |

The supplied list has 14 unique IDs because two supplied entries were duplicates. It covers Navigation Item, Button, Status Badge, Input, Metric Card, Table Row, Alert, Drawer Shell and App Shell; no separately supplied Progress Indicator node was available, so the `progress` fragment is a semantic primitive only and is not asserted as a distinct Figma component.

## Visual QA profile

Activate `visual-qa` to use `target/visual-qa.sqlite`, a fixed UTC clock, disabled scheduler, disabled AgentBridge runner, and visual fixture location. The regular production defaults retain the real database path and enabled scheduler. Compare a baseline and rendered image with:

```sh
python3 scripts/visual-regression.py baseline.png actual.png --tolerance 16 --max-diff-ratio 0.02
```

Only generated diff artifacts are written under `target/visual-regression/`.
