# 子 agent 质量摘要 JSON

子 agent 必须把质量发现项写入 `detail.output.quality_summary_json`，按 MCP workflow 将 `detail.output.quality_summary_marker` 替换为合法 JSON。子 agent 只负责发现证据，不负责最终计分。

子 agent 不得写入 `quality_adjustment_percent`。子 agent 不得写入 `components[].score`。主 agent 必须使用脚本统一计算质量分，避免不同子 agent 使用不同评分尺度。

质量摘要必须使用以下结构：

```json
{
  "author": "",
  "status": "completed",
  "findings": [
    {
      "id": "",
      "dimension": "code_standard",
      "polarity": "negative",
      "severity": "medium",
      "rule_id": "",
      "file": "",
      "line_start": 0,
      "line_end": 0,
      "evidence": "",
      "reason": "",
      "suggestion": ""
    }
  ],
  "positive_signals": [],
  "risk_signals": [],
  "code_snippets": [
    {
      "file": "",
      "line_start": 0,
      "line_end": 0,
      "dimension": "code_standard",
      "severity": "medium",
      "reason": "",
      "suggestion": "",
      "snippet": ""
    }
  ],
  "unverified": [],
  "summary": ""
}
```

## Finding 字段规则

- `dimension` 只能是 `code_standard`、`maintainability`、`risk_control` 或 `reviewability`。
- `polarity` 只能是 `positive` 或 `negative`。
- `severity` 只能是 `low`、`medium` 或 `high`。
- `rule_id` 必须稳定、可聚合，用于表示问题或正向信号类型，例如 `missing_boundary_check`、`clear_reuse_boundary`、`hard_to_review_batch_change`。
- `evidence` 必须写明证据，不得只写结论。
- 缺少证据的维度不要写 finding，写入 `unverified` 说明原因。

维度说明：

- `"dimension": "code_standard"`：代码规范，评价命名、格式、分层、异常处理、日志、SQL/XML/YAML 写法和项目约定遵循情况。
- `"dimension": "maintainability"`：可维护性，评价复用边界、重复逻辑、公共代码、工具类代码、调用链和调用点证据。公共代码加分必须写明实际使用的 MCP 工具名、公共代码文件和代表性调用点。
- `"dimension": "risk_control"`：风险控制，评价兼容性、空值/异常处理、日志、边界保护、迁移说明和公共代码影响面。公共代码被多处调用时也代表影响面更大，不能只因被调用多而单向写正向 finding。
- `"dimension": "reviewability"`：可审查性，评价提交范围、批量变更、格式化、生成代码、混合主题和审查难度。

## 统一计分规则

主 agent 必须使用脚本统一计算质量分：

```powershell
python <path-to-this-skill>\scripts\git_code_contribution_report.py score-quality <quality-summary.json>
```

脚本根据 `findings[]` 统一计算 `components[].score` 和 `quality_adjustment_percent`，子 agent 输出中的任何手写分数都无效。

统一分值表：

| polarity | low | medium | high |
| --- | ---: | ---: | ---: |
| negative | -2 | -5 | -8 |
| positive | +1 | +3 | +5 |

维度封顶：

- `code_standard` 限制在 `[-8, 8]`。
- `maintainability` 限制在 `[-8, 8]`。
- `risk_control` 限制在 `[-8, 8]`。
- `reviewability` 限制在 `[-6, 6]`。
- `quality_adjustment_percent` 等于各维度统一计分之和，并限制在 `[-30, 30]`。

## 低质量代码片段规则

- `code_snippets` 只记录低质量代码片段；没有明确问题时使用空数组。
- 只要写入 `code_snippets`，必须同时写入对应的负向 finding；如果遗漏，脚本按 `low_quality_code_snippet` 规则补充一个负向 finding，统一计分结果必须小于 0。
- 每个人最多 3 个低质量代码片段，每个片段最多 12 行，不得粘贴完整文件。
- 片段必须来自 `detail.changed_regions[].hunk`，并写明 `file`、`line_start`、`line_end`、`dimension`、`severity`、`reason` 和 `suggestion`。
- 不得包含密钥、令牌、密码、手机号、身份证号、银行卡号；发现敏感信息时用 `[REDACTED]` 替代，并在 `risk_signals` 中说明。
- 不确定行号时 `line_start` 和 `line_end` 使用 `0`，并在 `reason` 中说明定位依据。

## 质量分析允许读取的代码范围

- 质量分析只能基于 `detail.changed_regions` 中脚本从该人员提交提取出的 hunk。
- 优先使用 OpenCode `explore` 做上下文探索，当前作者 session 只消费 `explore` 返回的短证据摘要、文件路径、符号名或调用点位置；`explore` 不得返回完整文件、大段源码或未压缩搜索结果。
- 不得把完整文件中不属于 `detail.changed_regions` 的代码归因给该人员。
- `detail.top_files` 只作为统计表和工作量结构输入，不作为质量分析代码来源。
- 为判断公共代码、工具类代码是否被多个调用点使用，可以额外按 MCP workflow 做引用或调用链取证，并读取最多 5 个候选调用点文件；只能记录与该公共代码直接相关的调用证据。
- 禁止为了质量分析额外读取 Markdown、Office、普通文档、媒体或归档文件；这些文件不属于统计口径。
- 不扫描全项目，不读取其他人员文件清单。
- MCP 无法读取代码时，不写 finding，并写入 `unverified`。
