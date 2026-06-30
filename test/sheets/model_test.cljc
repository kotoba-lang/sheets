(ns sheets.model-test
  (:require [clojure.test :refer [deftest is]]
            [sheets.model :as s]
            [sheets.validate :as v]))

(deftest workbook-model
  (let [tab (-> (s/tab "plan")
                (s/put-cell 1 1 "A")
                (s/put-cell 1 2 "B"))
        wb (s/add-tab (s/workbook "wb") tab)]
    (is (= [[ "A" "B" ]] (s/range-values (s/tab-by-id wb "plan") 1 1 1 2)))
    (is (v/valid? wb))))
