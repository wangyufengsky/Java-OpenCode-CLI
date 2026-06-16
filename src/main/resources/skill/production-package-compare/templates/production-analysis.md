# Production Package Analysis: {left_branch} vs {right_branch}

## Overall Conclusion

- Overall risk: {overall_risk}
- Summary: {summary}

## Package Summary

| Package | Status | Total Differences | High Risk | Medium Risk | Low Risk | Analysis |
| --- | --- | ---: | ---: | ---: | ---: | --- |
{package_summary_rows}

## Missing Or Failed Packages

| Package | Issue | Required Action |
| --- | --- | --- |
{missing_or_failed_rows}

## Cross-Package High-Risk Changes

| Package | Path | Risk | Reason | Evidence |
| --- | --- | --- | --- | --- |
{cross_package_high_risk_rows}

## Configuration Changes Summary

{config_summary}

## Script And Startup Changes Summary

{script_summary}

## Concrete Content Changes Summary

{content_change_summary}

## Dependency And Binary Changes Summary

{dependency_summary}

## Manual Production Review Checklist

- {review_item_1}
- {review_item_2}
- {review_item_3}
- {review_item_4}

## Source Links

- Preparation summary: {summary_json_link}
- Task list: {tasks_json_link}
- Basic index: {index_md_link}
{package_links}
