# 模块代码审查：{{module}}

## 问题摘要

| 严重级别 | 标题 | 新代码位置 | 依据 | 影响 |
| --- | --- | --- | --- | --- |
{{FINDING_ROWS}}

## 审查范围

- 模块：`{{module}}`
- 重构项目：`{{new_project}}`
- 详细设计：`{{reconstructed_design}}`
- 证据来源：仅使用当前模块相关的新代码、调用方、被调用方、配置、SQL、数据库证据和重构详细设计；不要求交易名、映射文档或 old-8583-doc 中存在对应交易。

## 详细报告链接

| 内容 | 文件 |
| --- | --- |
| 详细问题 | `sections/01-findings.md` |
| 新代码调用链 | `sections/02-code-chains.md` |
| 模块职责与依赖审查 | `sections/03-protocol-review.md` |
| 模块行为与风险审查 | `sections/04-behavior-review.md` |
| 最小验证测试 | `sections/05-verification.md` |
| 代码规范审查 | `sections/06-code-standard.md` |
| 模块职责与依赖矩阵 | `mapping-matrix.md` |

## 未验证范围摘要

{{UNVERIFIED_SUMMARY}}

## 最小验证测试

{{VERIFICATION_TESTS_SUMMARY}}

## 总结

{{SUMMARY}}
