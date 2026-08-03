# MyBatis SQL Review Data Quality

- Status: `complete`
- Mapper inventory entries: `1`
- SQL task entries: `4`
- Validated SQL summaries: `4`
- Publication gate: every mapped statement has report.md, summary.json, and database-evidence.json

## Evidence boundary

Detailed reports retain native Database MCP tool names and normalized `data_source`, `catalog`, `schema`, `project`, and `scope` arguments. Findings are review results and do not cause a technical workflow failure. Missing discovery, database, tool-call audit, candidate artifacts, schema validation, or aggregate link targets fail the run before stable publication.
