# 交易流程分析：{transaction_key}

## 概述

- 交易 XML: `{transaction_xml}`
- 原始交易引用: `{transaction_ref}`
- 匹配用交易引用: `{transaction_match_ref}`
- 主 case: `{primary_case}`
- 复用该交易的 case: `{aliases}`
- base 模块数: `{module_count}`

{overview}

## 详细流程

{detailed_flow}

## 流程图

图例：该图使用 Mermaid 时序图展示交易入口、交易编排和 base 模块之间的调用关系；`alt`/`else` 表示条件分支。为保证稳定渲染，箭头不使用 activation 标记。完整 XML 路径、条件、类名和长 id 放在“节点说明”表中。

```mermaid
{mermaid_source}
```

## 节点说明

| 步骤 | XML 节点 | 条件 | serviceId / 目标 | 说明 | 模块文档 |
| --- | --- | --- | --- | --- | --- |
{node_rows}

## 模块链接

| serviceId | 模块文档 | 复用说明 |
| --- | --- | --- |
{module_rows}

## 总结

{summary}
