# 个人代码提交量报告：`<author>`

## 1. 基本统计

| 指标 | 数值 |
| --- | ---: |
| 排名 | `<rank>` |
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
| 基础工作量分 | `<base_workload_score>` |

## 2. 工作量结构分析

结合提交主题、归属变更、扩展名分布和去注释代码行，分析该人员在统计期内更偏向：

- 新增开发。
- 重构调整。
- 缺陷修复。
- 配置脚本修改。
- 删除清理。
- 混合型工作。

不要把基础工作量分写成绩效结论。个人报告不展示质量调整百分比或质量调整后的最终工作量分；这些字段只由主 agent 统一计分后写入总报告。

## 3. 归属变更与扫描证据

| 文件 | Commit | 归属行区间 | 去注释新增行 | 扫描归因 | 分析 |
| --- | --- | ---: | ---: | --- | --- |
| `<path>` | `<short_hash>` | `<line_start>-<line_end>` | `<non_comment_added>` | `<scanner_rule>` | `<analysis>` |

## 4. 扩展名分布

| 扩展名 | 文件修改次数 | 去注释新增行 | 去注释删除行 | 分析 |
| --- | ---: | ---: | ---: | --- |
| `<extension>` | `<file_change_count>` | `<non_comment_added>` | `<non_comment_deleted>` | `<analysis>` |

## 5. 主要提交

| 日期 | Commit | 主题 | 分析 |
| --- | --- | --- | --- |
| `<date>` | `<short_hash>` | `<subject>` | `<analysis>` |

## 6. 偏差与注意事项

说明该人员统计中是否存在可能放大或压低工作量的因素，例如：

- 大规模格式化。
- 生成文件或锁文件。
- 配置脚本类变更占比较高。
- 文档、Office、普通文本、媒体和归档类变更已排除，不得作为工作量或质量依据。
- 批量迁移或批量删除。
- 作者别名未合并。
- 注释过滤口径误差。

## 7. 代码质量与风险信号

| 维度 | 类型 | 严重程度 | 规则 | 证据 |
| --- | --- | --- | --- | --- |
| `<dimension>` | `<polarity>` | `<severity>` | `<rule_id>` | `<evidence>` |

维度只能使用 `code_standard`（代码规范）、`maintainability`（可维护性）、`risk_control`（风险控制）或 `reviewability`（可审查性）。

质量调整百分比由主 agent 使用统一评分脚本计算，个人报告不得手写质量调整分。

正向信号：

- `<positive_signal>`

风险信号：

- `<risk_signal>`

### 7.1 低质量代码片段

低质量代码片段同时写入 `quality-summary.json.code_snippets`。每个人最多 3 个低质量代码片段，每个片段最多 12 行，不得粘贴完整文件。片段必须来自 Java 证据包已摘录和脱敏的内容。不得包含密钥、令牌、密码、手机号、身份证号、银行卡号；如发现敏感内容，用 `[REDACTED]` 替代。

#### 片段 `<index>`：`<file>:<line_start>-<line_end>`

- 维度：`<dimension>`
- 严重程度：`<severity>`
- 原因：`<reason>`
- 建议：`<suggestion>`

```text
<snippet>
```

没有可安全摘录的片段时写：未发现可安全摘录的低质量代码片段。

未验证项：

- `<unverified>`
