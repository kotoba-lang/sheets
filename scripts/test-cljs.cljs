#!/usr/bin/env nbb
;; The same test namespaces, on the other host.
;;
;; `clojure -M:test` runs them on the JVM, which is where they were written
;; and where they all passed while `column-name` and `column-number` were
;; both wrong under ClojureScript. `(int \A)` is a code point on the JVM and
;; 0 in cljs, so a workbook written from nbb had control characters for cell
;; references and one read there put every column in column 1 — silently,
;; because column A is 1 either way and `AA` came out as 27 by coincidence.
;;
;; A `.cljc` library that only ever runs on one host is a `.clj` library with
;; extra reader conditionals. This runs the portable ones on the other host,
;; and the `#?(:clj …)` tests — the zip round-trips — are simply absent here,
;; which is the correct outcome rather than a gap.
;;
;;   nbb --classpath "$(clojure -Spath):test" scripts/test-cljs.cljs
;;
;; or, from a checkout with the deps resolved:
;;
;;   scripts/test-cljs.cljs

(require '[clojure.test :as t]
         'sheets.chart-test
         'sheets.csv-test
         'sheets.formula-test
         'sheets.model-test
         'sheets.xlsx-test)

(let [{:keys [fail error]} (t/run-tests 'sheets.chart-test
                                        'sheets.csv-test
                                        'sheets.formula-test
                                        'sheets.model-test
                                        'sheets.xlsx-test)]
  (when (pos? (+ (or fail 0) (or error 0)))
    (js/process.exit 1)))
