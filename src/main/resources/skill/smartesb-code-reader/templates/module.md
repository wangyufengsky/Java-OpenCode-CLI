# base 模块分析：{serviceId}

## 概述

- serviceId: `{serviceId}`
- base XML 候选: `{base_xml_candidates}`
- biz 小流程候选: `{biz_candidates}`
- Java 候选: `{java_candidates}`
- 被以下交易使用: `{used_by_transactions}`

{overview}

## 输入证据

| 类型 | 路径 / 标识 | 结论 | 备注 |
| --- | --- | --- | --- |
{evidence_rows}

## 定位与消歧

{resolution}

## base XML 摘要

| 配置项 | 值 | 说明 |
| --- | --- | --- |
{base_xml_rows}

## biz 小流程

| 顺序 | adapter / 节点 | 类型 | Java 线索 | 说明 |
| ---: | --- | --- | --- | --- |
{biz_flow_rows}

## 入参

| 名称 | 来源 | 类型 / 结构 | 说明 |
| --- | --- | --- | --- |
{input_rows}

## 上下文与变量读写

| 变量 / 字段 | 来源 | 读 / 写 | 用途 | 证据 |
| --- | --- | --- | --- | --- |
{variable_rows}

## Java 主流程

| 步骤 | 方法 / 位置 | 操作 | 关键读写 | 分支 / 条件 | 证据 |
| ---: | --- | --- | --- | --- | --- |
{main_flow_rows}

## 详细说明

{detailed_flow}

## 外部依赖

| 类型 | 目标 | 调用位置 | 入参 | 返回 / 副作用 |
| --- | --- | --- | --- | --- |
{external_call_rows}

## 异常、返回码与降级

| 场景 | 触发条件 | 处理方式 | 返回 / 输出 | 证据 |
| --- | --- | --- | --- | --- |
{error_rows}

## 输出与副作用

| 输出项 | 目标位置 | 写入值 / 结构 | 写入时机 | 证据 |
| --- | --- | --- | --- | --- |
{output_rows}

## 流程图

图例：该图使用 Mermaid 时序图展示入口方法、上下文/变量、核心处理器和外部依赖之间的调用关系；`alt`/`else`/`opt` 表示条件、异常或可选路径。为保证稳定渲染，箭头不使用 activation 标记。完整类名、方法签名、路径和长表达式放在“Java 主流程”和“详细说明”中。

```mermaid
{mermaid_source}
```

## 被交易使用

| 交易 | 使用方式 | 备注 |
| --- | --- | --- |
{used_by_rows}

## 风险与不确定项

{risks_or_uncertainties}

## 总结

{summary}
