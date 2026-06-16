# 代码提交量统计报告

## 1. 统计范围

| 项目 | 内容 |
| --- | --- |
| 仓库 | `<repo>` |
| 修订范围 | `<revision>` |
| 开始日期 | `<since>` |
| 结束日期 | `<until>` |
| 是否包含 merge commit | `<include_merges>` |
| 默认统计白名单 | `<default_include_rules>` |
| 用户追加统计白名单 | `<user_include_rules>` |
| 默认排除规则 | `<default_exclude_rules>` |
| 用户追加排除规则 | `<user_exclude_rules>` |
| 生成时间 | `<generated_at>` |

## 2. 总体汇总

| 指标 | 数值 |
| --- | ---: |
| 提交数 | `<commit_count>` |
| 文件修改次数 | `<file_change_count>` |
| 去重文件数 | `<unique_file_count>` |
| 原始新增行 | `<added>` |
| 原始删除行 | `<deleted>` |
| 原始净变更行 | `<net>` |
| 去注释新增行 | `<non_comment_added>` |
| 去注释删除行 | `<non_comment_deleted>` |
| 去注释净变更行 | `<non_comment_net>` |
| 去注释代码变更量 | `<non_comment_churn>` |

## 3. 人员工作量排名与分析

### 3.1 排名表

先计算每个人的质量调整后 `workload_score`，再按质量调整后的 `workload_score` 降序排序生成最终排名。脚本初始 `rank` 只能作为初始排名展示，不能作为最终排名表的排序依据。

| 最终排名 | 初始排名 | 开发人员 | 提交数 | 文件修改次数 | 去重文件数 | 去注释新增行 | 去注释删除行 | 基础工作量分 | 质量调整 | 最终工作量分 |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `<final_rank>` | `<base_rank>` | `<author>` | `<commit_count>` | `<file_change_count>` | `<unique_file_count>` | `<non_comment_added>` | `<non_comment_deleted>` | `<base_workload_score>` | `<quality_adjustment_percent>%` | `<workload_score>` |

### 3.2 AI 分析结论

按最终排名顺序和每个人的 `person-report.md` 分析工作量结构。每个人至少说明：

- 主要贡献类型：新增开发、重构调整、缺陷修复、配置脚本修改、删除清理或混合型工作。
- 统计依据：引用提交数、文件修改次数、去注释新增/删除/净变更行，并引用个人报告中的 Top 文件或扩展名分布结论。
- 质量依据：引用 `quality-summary.json` 中的质量调整、正向信号、风险信号和未验证项。
- 口径提醒：如存在格式化、生成文件、依赖锁文件、批量迁移、批量删除等可能影响排名的因素，明确说明。

### 3.3 质量调整口径

最终 `workload_score` 使用以下公式：

```text
workload_score = round(base_workload_score * (1 + quality_adjustment_percent / 100), 2)
```

`quality_adjustment_percent` 来自 Java 调度器对每个人 `quality-summary.json.findings[]` 执行统一评分后的结果，取值范围限制在 `[-30, 30]`。子 agent 手写分数无效。质量调整只作为工作量排序的辅助修正，不代表绩效评价。

## 4. 个人报告链接

| 排名 | 开发人员 | 个人报告 | 简要结论 |
| ---: | --- | --- | --- |
| `<rank>` | `<author>` | `<report_markdown_link>` | `<summary>` |

## 5. 未完成个人报告

| 开发人员 | 个人报告链接 | 状态 | 处理要求 |
| --- | --- | --- | --- |
| `<author>` | `<report_markdown_link>` | `<status>` | `<next_action>` |

## 6. 统计口径

- 统计区间按开始日期 `00:00:00` 到结束日期 `23:59:59` 包含边界。
- 默认排除 merge commit；只有脚本传入 `--include-merges` 时才包含。
- 只统计命中 `metadata.include` 且未命中 `metadata.exclude` 的开发相关文件。
- 默认统计白名单包含 Java、Python、JavaScript/TypeScript、HTML/CSS、XML、YAML/YML、JSON、SQL、Shell、C/C++、Go、Rust、PHP、Proto、GraphQL、Dockerfile、Makefile、Jenkinsfile 和常见开发配置文件。
- Markdown、Excel、Word、PPT、PDF、普通文本、媒体和归档类文件不计入提交数、文件修改次数、去重文件数、原始行数、去注释行数、Top 文件、扩展名分布、基础工作量分或最终工作量分。
- 只修改非统计文件的提交不计入 `commit_count`；混合提交只统计符合计入条件的开发文件。
- `文件修改次数` 是提交内文件变更次数，不等同于去重文件数。
- `去注释新增行`、`去注释删除行` 会过滤明显注释行和空行，但不保证等同于严格语法解析。
- `最终工作量分` 只用于报告排序辅助，不代表绩效评价。
- `基础工作量分` 由脚本按提交数、文件修改次数、去注释新增/删除行计算。
- `质量调整` 来自 Java 调度器统一评分后的结果，最多调整基础分的正负 30%。
- `最终工作量分` 是总报告排名使用的 `workload_score`。

## 7. 风险与偏差

列出可能影响统计准确性的因素，包括但不限于：

- 大规模格式化或统一换行符。
- 生成文件、依赖锁文件、编译产物或压缩文件。
- 批量迁移、批量删除旧代码。
- 作者邮箱未统一导致同一人被拆分。
- 注释过滤为启发式规则，复杂语言语法可能存在误差。

## 8. 典型低质量代码片段

从各 `quality-summary.json.code_snippets` 中选取最多 10 个低质量代码片段。个人报告中的 `code_snippets` 每人最多 3 个；总报告每个开发人员最多引用 2 个，每个片段最多 12 行，不得粘贴完整文件。不得包含密钥、令牌、密码、手机号、身份证号、银行卡号；如发现敏感内容，用 `[REDACTED]` 替代。

### 8.`<index>` `<author>`：`<file>:<line_start>-<line_end>`

- 维度：`<dimension>`
- 严重程度：`<severity>`
- 原因：`<reason>`
- 建议：`<suggestion>`
- 个人报告：`<report_markdown_link>`

```text
<snippet>
```

没有可安全摘录的片段时写：未发现可安全摘录的低质量代码片段。

## 9. 附录

- 事实摘要：`summary.json`
- 编排输入：`index_inputs.json`
- 个人明细目录：`details/`
- 个人报告目录：`reports/`
- 质量摘要：`reports/author-*/quality-summary.json`
- 脚本预览：`index.md`
