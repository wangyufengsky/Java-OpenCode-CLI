# Beyond Compare Script Notes

Beyond Compare script files are plain text files passed to `BCompare.exe` with the `@` prefix:

```powershell
& $env:BCOMPARE_PATH '@C:\path\script.bcscript' /silent
```

Use `/silent` so Beyond Compare runs the script without showing the script status window.

Core commands used by this skill:

```text
log verbose "C:\report\raw\bcompare.log"
criteria rules-based
load "C:\left.tar" "C:\right.tar"
expand all
folder-report layout:xml output-to:"C:\report\raw\folder.xml"
folder-report layout:side-by-side options:display-mismatches output-to:"C:\report\raw\folder.html" output-options:html-color
```

For individual changed files, the helper generates scripts in this shape:

```text
log verbose "C:\report\raw\file.log"
criteria rules-based
load "C:\left.tar" "C:\right.tar"
expand all
select "path\inside\archive.txt"
file-report layout:side-by-side options:display-mismatches output-to:"C:\report\raw\file.html" output-options:html-color
text-report layout:patch options:patch-unified output-to:"C:\report\raw\file.diff"
```

Notes:

- `expand all` is required before selecting nested archive content.
- `folder-report layout:xml` is used as machine-readable input for Markdown generation.
- `folder-report layout:side-by-side` is kept as the human-readable raw Beyond Compare report.
- `text-report layout:patch` can fail or produce no useful content for binary/non-text files; the Markdown generator treats that as a non-fatal condition.
- Paths in scripts must be quoted. A literal double quote inside a path is not supported by this helper.
