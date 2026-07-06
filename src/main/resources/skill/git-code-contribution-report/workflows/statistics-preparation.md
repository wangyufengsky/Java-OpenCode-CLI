# 统计准备脚本

从目标 Git 仓库根目录运行统计准备脚本，不要切到 skill 安装目录作为工作目录。运行环境按 Windows Python 3.11 设计。

```powershell
python <path-to-this-skill>\scripts\git_code_contribution_report.py `
  --repo <git-repo> `
  --since <YYYY-MM-DD> `
  --until <YYYY-MM-DD> `
  --out <output-dir>
```

常用可选参数：

- `--revision <rev>`：指定统计修订范围，默认 `HEAD`。
- `--all`：统计所有 refs 上的提交。
- `--include-merges`：包含 merge commit；默认排除 merge commit。
- `--author-map <json>`：合并同一人员的多个 Git 作者名或邮箱。
- `--include <glob>`：追加统计白名单，仅用于开发相关文件模式，可重复传入。命中的文件仍不能命中任何排除规则。
- `--exclude <glob>`：追加排除文件模式，可重复传入，例如 `--exclude 'target/**' --exclude '*.lock'`。Markdown、Office、普通文档、媒体和归档文件已默认排除。

## 默认统计白名单

```text
*.java *.kt *.kts *.scala *.groovy *.gradle
*.py *.rb *.sh *.bash *.zsh *.ps1 *.bat *.cmd
*.js *.jsx *.ts *.tsx *.mjs *.cjs *.vue *.svelte
*.html *.htm *.xhtml *.css *.scss *.sass *.less *.jsp *.jspx
*.xml *.yml *.yaml *.json *.toml *.ini *.conf *.properties *.sql
*.c *.cc *.cpp *.cxx *.h *.hpp *.cs *.go *.rs *.swift *.php *.lua *.r *.pl
*.proto *.graphql *.gql
Dockerfile Dockerfile.* Makefile makefile GNUmakefile Jenkinsfile Jenkinsfile.*
.gitignore .gitattributes .dockerignore .editorconfig
```

默认排除：

```text
*.md
*.markdown
*.mdown
*.mkd
*.doc
*.docx
*.xls
*.xlsx
*.xlsm
*.ppt
*.pptx
*.pdf
*.rtf
*.txt
*.csv
*.png
*.jpg
*.jpeg
*.gif
*.bmp
*.webp
*.ico
*.zip
*.tar
*.gz
*.7z
*.rar
```

最终计入条件必须同时满足：文件路径命中 `metadata.include`，且不命中 `metadata.exclude`。Markdown、Excel、Word、PPT、PDF、普通文本、媒体和归档类文件不计入任何统计和分数。只修改非统计文件的提交不计入 `commit_count`；混合提交只统计符合计入条件的开发文件。

`--out` 必须使用 Windows 绝对路径，并确保该路径能被 AgentBridge MCP 读取和写入，例如：

```powershell
D:\review-output\git-code-contribution-20260612
```

脚本会生成并预创建：

```text
summary.json
details.json
index_inputs.json
index.md
code-contribution-report.md
details\author-001-xxx.json
reports\author-001-xxx\person-report.md
reports\author-001-xxx\quality-summary.json
```

## Comment Filtering Scope

只有命中 `metadata.include` 且未命中 `metadata.exclude` 的文件才进入注释过滤、文件数、行数、排名和分数。Markdown、Office、普通文档、媒体和归档文件默认不进入统计口径。

脚本只过滤明显的注释行和空行，不修改源码：

- Java/C/JS/TS/Go/Kotlin/Scala/SQL 等：过滤明显的 `//`、`/* ... */`、`--` 注释行。
- Python/Shell/YAML/TOML/INI 等：过滤明显的 `#` 注释行。
- XML/HTML/JSP/Vue 等：过滤明显的 `<!-- ... -->` 注释行。
- Properties：过滤明显的 `#` 和 `!` 注释行。

复杂场景可能不会完全准确，例如字符串里的注释符、跨 diff hunk 的块注释、语言特有文档注释和格式化导致的大规模变更。最终报告必须保留这一口径说明。

## Author Map

需要合并作者别名时，创建 JSON 文件并传给 `--author-map`。该文件必须使用以下两种结构之一。

结构一：按人员列出别名数组。

```json
{
  "aliases": {
    "张三": ["zhangsan", "zhangsan@example.com", "Zhang San <zhangsan@example.com>"],
    "李四": ["lisi", "li.si@example.com"]
  }
}
```

结构二：按单个别名映射到标准人员名。

```json
{
  "zhangsan@example.com": "张三",
  "Zhang San <zhangsan@example.com>": "张三"
}
```
