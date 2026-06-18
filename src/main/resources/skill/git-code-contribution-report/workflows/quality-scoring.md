# 子 agent 质量摘要 JSON

`quality-summary.json` 已由统计脚本预生成。子 agent 不得写入 `quality-summary.json`。子 agent 不得写入 `quality_adjustment_percent`，不得写入 `detail.output.quality_summary_json`，不得写入 `components[].score`。

子 agent 只负责在个人报告中分析质量风险和结论，不负责发现负向扣分项，不负责最终计分。主 agent 必须使用脚本统一计算质量分：

```powershell
python <path-to-this-skill>\scripts\git_code_contribution_report.py score-quality <quality-summary.json>
```

质量摘要保留以下结构，供主 agent 和评分脚本读取：

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
- `"dimension": "code_standard"` 表示代码规范。
- `polarity` 只能是 `positive` 或 `negative`。
- `severity` 只能是 `low`、`medium` 或 `high`。
- `rule_id` 必须稳定、可聚合。
- `evidence` 必须写明证据，不得只写结论。

## 统一计分规则

统一分值表：

| polarity | low | medium | high |
| --- | ---: | ---: | ---: |
| negative | 0 | -1 | -2 |
| positive | +1 | +3 | +5 |

维度封顶：

- `code_standard` 限制在 `[-8, 8]`。
- `maintainability` 限制在 `[-8, 8]`。
- `risk_control` 限制在 `[-8, 8]`。
- `reviewability` 限制在 `[-6, 6]`。
- `quality_adjustment_percent` 等于各维度统一计分之和，并限制在 `[-30, 30]`。

## 低质量代码片段规则

- `code_snippets` 由统计脚本或 Java 扫描归因预生成；没有明确问题时使用空数组。
- `code_snippets` 必须对应已有负向 finding；脚本不会根据片段自动补充扣分 finding。
- 每个人最多 3 个低质量代码片段，每个片段最多 12 行，不得粘贴完整文件。
- 报告展示同类扫描问题时只放一个代表代码片段，并说明类似片段数量；不得把同一规则、同一原因的重复片段逐条展开。
- 片段必须来自已摘录和脱敏的证据，并写明 `file`、`line_start`、`line_end`、`dimension`、`severity`、`reason` 和 `suggestion`。
- 不得包含密钥、令牌、密码、手机号、身份证号、银行卡号；发现敏感信息时用 `[REDACTED]` 替代。

## 质量分析代码读取边界

- 子 agent 不得读取业务代码、源码文件或按文件路径自行补证。
- 负向质量发现、代码片段、行号和归属只能使用已生成的 `pmdDetail.attributed_findings` 与 `pmdDetail.code_snippets`。
- 禁止为了质量分析额外读取 Markdown、Office、普通文档、媒体或归档文件。
- 扫描失败、未归因风险或证据不足时，只能写入个人报告风险说明，不新增负向扣分 finding。
