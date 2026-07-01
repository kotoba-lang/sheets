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
        envelope (wire/workbook-envelope wb {:request-id "req-1"})]
    (is (v/valid? wb))
    (is (= wb (wire/read-workbook-envelope (:body envelope))))))
