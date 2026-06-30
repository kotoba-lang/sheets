# sheets

Portable CLJC spreadsheet workspace model for `sheets.gftd.ai`.

Cells are EDN facts keyed by sheet/range coordinates. Formula evaluation is
host-injected later; the core provides deterministic addressing and tabular data.

## Test

```bash
clojure -X:test
```
