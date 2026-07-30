(ns sheets.model-test
  (:require [clojure.test :refer [deftest is]]
            [sheets.model :as s]
            [sheets.validate :as v]
            [sheets.wire :as wire]))

(deftest workbook-model
  (let [tab (-> (s/tab "plan")
                (s/put-cell 1 1 "A")
                (s/put-cell 1 2 "B"))
        wb (s/add-tab (s/workbook "wb") tab)]
    (is (= [[ "A" "B" ]] (s/range-values (s/tab-by-id wb "plan") 1 1 1 2)))
    (is (v/valid? wb))))

(deftest workbook-semantics-and-transit-wire
  (let [wb (-> (s/workbook "wb")
               (s/add-tab (-> (s/tab "plan")
                              (s/put-cell 1 1 "Quarter")
                              (s/put-cell 1 2 "Revenue")
                              (s/put-formula 2 2 "SUM(B3:B6)")
                              (s/put-cell-style 1 1 {:bold true})))
               (s/add-named-range "revenue" {:sheets/tab "plan"
                                              :sheets/range "B2:B6"})
               (s/add-chart {:sheets/id "revenue-chart"
                             :sheets/type :bar
                             :sheets/data-range "plan!A1:B6"}))
        envelope (wire/workbook-envelope wb {:request-id "req-1"})
        projected (wire/read-workbook-envelope (:body envelope))]
    (is (v/valid? wb))
    ;; What the wire actually carries: string keys, and the cell address
    ;; flattened into one. Asserted rather than skipped past, because this is
    ;; the shape every consumer of the envelope receives.
    (is (= "workbook" (get projected "sheets/type")))
    (is (= #{"[1 1]" "[1 2]" "[2 2]"}
           (set (keys (get-in projected ["sheets/tabs" "plan" "sheets/cells"])))))
    ;; And closed again by a reader that knows the schema.
    (is (= wb (wire/rehydrate-workbook projected)))
    (is (= wb (wire/workbook-of-envelope (:body envelope))))
    (is (v/valid? (wire/workbook-of-envelope (:body envelope))))))

(deftest cell-addresses-round-trip-both-ways
  (is (= [1 1] (wire/cell-address "[1 1]")))
  (is (= [12 340] (wire/cell-address (wire/cell-address-string [12 340]))))
  ;; Not everything that arrives is one, and a key that is not an address is
  ;; carried rather than dropped.
  (is (= "plan!A1" (wire/cell-address "plan!A1"))))

(deftest a-malformed-payload-is-handed-on-rather-than-thrown-at
  (doseq [payload [{"sheets/tabs" "nope"}
                   {"sheets/tabs" {"plan" "not-a-tab"}}
                   {"sheets/tabs" {"plan" {"sheets/cells" "nope"}}}
                   {"sheets/named-ranges" "nope"}
                   {"sheets/charts" "nope"}
                   {"sheets/type" 7}
                   "not-a-workbook-at-all"]]
    (is (some? (wire/rehydrate-workbook payload)) (str "survived: " (pr-str payload)))))
