# 个人代码提交量报告：{{author}}

## 1. 基本统计

| 指标 | 数值 |
| --- | ---: |
| 排名 | {{rank}} |
| 提交数 | {{commit_count}} |
| 文件修改次数 | {{file_change_count}} |
| 去重文件数 | {{unique_file_count}} |
| 原始新增行 | {{added}} |
| 原始删除行 | {{deleted}} |
| 原始净变更行 | {{net}} |
| 去注释新增行 | {{non_comment_added}} |
| 去注释删除行 | {{non_comment_deleted}} |
| 去注释净变更行 | {{non_comment_net}} |
| 去注释代码变更量 | {{non_comment_churn}} |
| 基础工作量分 | {{base_workload_score}} |

## 2. 工作量结构分析

{{WORKLOAD_STRUCTURE_ANALYSIS}}

## 3. 归属变更与扫描证据

| 文件 | Commit | 归属行区间 | 去注释新增行 | 扫描归因 | 分析 |
| --- | --- | ---: | ---: | --- | --- |
{{OWNED_CHANGE_ROWS}}

## 4. 扩展名分布

| 扩展名 | 文件修改次数 | 去注释新增行 | 去注释删除行 | 分析 |
| --- | ---: | ---: | ---: | --- |
{{EXTENSION_ROWS}}

## 5. 主要提交

| 日期 | Commit | 主题 | 分析 |
| --- | --- | --- | --- |
{{COMMIT_ROWS}}

## 6. 偏差与注意事项

{{BIAS_NOTES}}

## 7. 代码质量与风险信号

| 维度 | 类型 | 严重程度 | 规则 | 证据 |
| --- | --- | --- | --- | --- |
{{QUALITY_FINDING_ROWS}}

正向信号：

{{POSITIVE_SIGNALS}}

风险信号：

{{RISK_SIGNALS}}

### 7.1 低质量代码片段

{{LOW_QUALITY_SNIPPETS}}

未验证项：

{{UNVERIFIED_ITEMS}}

## 8. 综合评价与结论

{{OVERALL_EVALUATION}}
