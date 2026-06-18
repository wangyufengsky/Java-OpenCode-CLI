# 交易重构代码审查：{{transaction}}

## 问题摘要

| 严重级别 | 标题 | 新代码位置 | 老代码位置 | 协议依据 | 业务影响 |
| --- | --- | --- | --- | --- | --- |
{{FINDING_ROWS}}

## 审查范围

- 交易：`{{transaction}}`
- 说明：{{description}}
- 老项目：`{{old_project}}`
- 重构项目：`{{new_project}}`
- 证据来源：仅使用新老项目代码、配置、XML、SQL 和数据库证据；不读取业务文档、协议文档或重构设计文档。

## 详细报告链接

| 内容 | 文件 |
| --- | --- |
| 详细问题 | `sections/01-findings.md` |
| 新老代码调用链 | `sections/02-code-chains.md` |
| 8583 到 JSON 协议审查 | `sections/03-protocol-review.md` |
| 行为等价性审查 | `sections/04-behavior-review.md` |
| 最小验证测试 | `sections/05-verification.md` |
| 代码规范审查 | `sections/06-code-standard.md` |
| 映射矩阵 | `mapping-matrix.md` |

## 未验证范围摘要

{{UNVERIFIED_SUMMARY}}

## 最小验证测试

{{VERIFICATION_TESTS_SUMMARY}}

## 总结

{{SUMMARY}}
