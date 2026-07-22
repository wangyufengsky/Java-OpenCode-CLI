# SQL Review

This report reviews `mapper-order-find-open` without executing the original mapper statement.

Source: `mappers/OrderMapper.xml`, namespace `com.example.OrderMapper`, statement `findOpen`, command `select`, selectKey `false`.

## Statement

The statement is a read-only lookup of open orders.

## Static Analysis

The status predicate can become expensive when its selectivity is low.

## Database Evidence

Evidence `E-1` confirms the target column and scenario `S-1` retained two representative rows.

## Findings

`F-1` records the index-verification risk.

## Recommendations

Confirm the index and compare a read-only execution plan in the target environment.

## Limitations

The audit is post-hoc and the retained sample does not represent production cardinality.
