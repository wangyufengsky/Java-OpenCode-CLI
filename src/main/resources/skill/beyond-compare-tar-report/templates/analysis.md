# {package} Tar Analysis

## Conclusion

- Overall risk: {overall_risk}
- Summary: {summary}

## Counts

| Category | Count |
| --- | ---: |
| Total differences | {total_differences} |
| Added / right-only | {added_count} |
| Deleted / left-only | {deleted_count} |
| Modified | {modified_count} |
| High risk | {high_risk_count} |
| Medium risk | {medium_risk_count} |
| Low risk | {low_risk_count} |

## High-Risk Files

| Path | Status | Reason | Evidence |
| --- | --- | --- | --- |
{high_risk_rows}

## Configuration Changes

| Path | Status | Notes | Detail |
| --- | --- | --- | --- |
{config_rows}

## Script Changes

| Path | Status | Notes | Detail |
| --- | --- | --- | --- |
{script_rows}

## Dependency And Binary Changes

| Path | Status | Type | Review Need |
| --- | --- | --- | --- |
{binary_rows}

## Class Bytecode Changes

| Path | Status | Method / Field / Bytecode Change | Evidence |
| --- | --- | --- | --- |
{class_rows}

## Important Text Diffs

{text_diff_sections}

## Concrete Content Changes

| Path | What Changed | Evidence |
| --- | --- | --- |
{content_change_rows}

## Manual Review Checklist

- {review_item_1}
- {review_item_2}
- {review_item_3}

## Source Links

- Parent report: {parent_report_link}
- Diff index: {diff_index_link}
- Raw folder HTML: {raw_folder_html_link}
- Raw folder XML: {raw_folder_xml_link}
