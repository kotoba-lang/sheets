# sheets

[![CI](https://github.com/kotoba-lang/sheets/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/sheets/actions/workflows/ci.yml)

Portable CLJC model for kotoba-lang/sheets.

Pages editor: https://kotoba-lang.github.io/sheets/

The Pages UI is local to kotoba-lang and does not redirect to external hosts.

## Compatibility direction

The workbook model keeps spreadsheet semantics needed for Google Sheets and
Excel-style roundtrips: tabs, cell values, formulas, cell style metadata, named
ranges, and chart descriptors. The shared wire format is the Kotoba office
envelope via `sheets.wire/workbook-envelope`, using `application/json` and the
`:sheets/workbook` resource kind.

## Coming back

The envelope is a lossy projection: `:sheets/type` leaves as `"workbook"` and
a cell address `[1 1]` leaves as the string `"[1 1]"`, while tab ids and
named-range ids are strings that must stay strings. Telling those apart needs
the schema, so the reader is here rather than in `transit`:

```clojure
(wire/workbook-of-envelope (:body envelope))   ; read + rehydrate
(wire/rehydrate-workbook projected)            ; if you already read it
(wire/cell-address-string [1 1])               ; "[1 1]", for reaching into a projection
```

Rehydrate before validating. `sheets.validate` reads namespaced keys, and on
a projected payload it finds none — reporting no problems rather than
reporting that it cannot see any.

## Test

```bash
clojure -X:test
```

## Kotoba bounded profile

`src/sheets/bounded_cells.kotoba` is a capability-free port of a single
tab's cell table, scoped to string-valued cells keyed by a bounded
`{:row :col}` integer coordinate — the shape `sheets.model-test`'s own
`workbook-model` fixture exercises. It uses `kotoba-lang/compiler`'s
canonical bounded typed-map (`[:map [:record :sheets/coord …] :string]`,
up to 31 entries) with a **record key** — the same primitive already
production-qualified by `kotoba-lang/crdt` and by the sibling
`kotoba-lang/states` bounded-model migration. Formulas, styles, named
ranges, charts, the workbook/tab container, `range-values`, the validator,
and the Transit wire envelope stay in the CLJC oracle (`sheets.model` /
`sheets.wire`). See [migration/bounded-cell-table-v1.edn](migration/bounded-cell-table-v1.edn).
