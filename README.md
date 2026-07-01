# sheets

[![CI](https://github.com/kotoba-lang/sheets/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/sheets/actions/workflows/ci.yml)

Portable CLJC model for kotoba-lang/sheets.

Pages editor: https://kotoba-lang.github.io/sheets/

The Pages UI is local to kotoba-lang and does not redirect to external hosts.

## Compatibility direction

The workbook model keeps spreadsheet semantics needed for Google Sheets and
Excel-style roundtrips: tabs, cell values, formulas, cell style metadata, named
ranges, and chart descriptors. The shared wire format is Kotoba Transit JSON via
`sheets.wire/workbook-envelope`, using `application/transit+json` and the
`:sheets/workbook` resource kind.

## Test

```bash
clojure -X:test
```
