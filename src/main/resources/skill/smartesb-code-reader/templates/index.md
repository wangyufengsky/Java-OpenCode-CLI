# Sm@rtESB 8583 交易索引

## 概览

- serviceIdentify: `{service_identify}`
- serviceIdentify 列表: `{service_identifies}`
- XML 根目录: `{xml_root}`
- Java 根目录: `{java_root}`
- 匹配模式: `{mode}`
- 原始 8583 case 数: `{raw_case_count}`
- 去重后交易数: `{deduped_transaction_count}`
- 唯一 base 模块数: `{unique_module_count}`

## 配置文件结构

{configuration_structure}

## 交易索引

| 交易 | 主 case | 复用该交易的 case | 模块数 | 文档 |
| --- | --- | --- | ---: | --- |
{transaction_rows}

## 重复交易归并

| 交易 | 归并键 | 复用 case |
| --- | --- | --- |
{duplicate_rows}

## 缺失或不确定引用

| 类型 | 引用 | 说明 |
| --- | --- | --- |
{missing_reference_rows}

## 总结

{summary}
