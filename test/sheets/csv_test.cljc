(ns sheets.csv-test
  (:require [clojure.test :refer [deftest is testing]]
            [sheets.csv :as csv]
            [sheets.model :as m]
            [sheets.validate :as v]))

(defn- plan []
  (-> (m/tab "plan" {:sheets/title "Plan"})
      (m/put-cell 1 1 "Quarter")
      (m/put-cell 1 2 "Revenue")
      (m/put-cell 2 1 "Q1")
      (m/put-cell 2 2 "1200")
      (m/put-formula 3 2 "SUM(B2:B2)")))

(deftest a-tab-writes-as-a-rectangle
  (is (= (str "Quarter,Revenue\r\n"
              "Q1,1200\r\n"
              ",=SUM(B2:B2)")
         (csv/tab->csv (plan))))
  ;; Padded to the widest column the tab uses: a reader meeting a short row
  ;; has to guess whether the cells are missing or the file is.
  ;; Three rows because the formula is on row 3, two columns because nothing
  ;; reaches further right.
  (is (= [3 2] (csv/tab-bounds (plan)))))

(deftest an-empty-tab-writes-nothing
  (is (= "" (csv/tab->csv (m/tab "empty"))))
  (is (= [0 0] (csv/tab-bounds (m/tab "empty")))))

(deftest quoting-follows-the-rfc
  (let [tab (-> (m/tab "t")
                (m/put-cell 1 1 "a,b")
                (m/put-cell 1 2 "say \"hi\"")
                (m/put-cell 1 3 "line\nbreak")
                (m/put-cell 1 4 "plain"))]
    (is (= "\"a,b\",\"say \"\"hi\"\"\",\"line\nbreak\",plain" (csv/tab->csv tab)))
    ;; And back again, unchanged.
    (is (= {[1 1] {:sheets/value "a,b"}
            [1 2] {:sheets/value "say \"hi\""}
            [1 3] {:sheets/value "line\nbreak"}
            [1 4] {:sheets/value "plain"}}
           (:sheets/cells (csv/csv->tab "t" (csv/tab->csv tab)))))))

(deftest a-tab-round-trips
  (let [back (csv/csv->tab "plan" (csv/tab->csv (plan)) {:sheets/title "Plan"})]
    (is (= (:sheets/cells (plan)) (:sheets/cells back)))
    (is (= "Plan" (:sheets/title back)))
    (is (v/valid? (m/add-tab (m/workbook "wb") back)))))

(deftest a-leading-equals-is-a-formula-in-both-directions
  (let [tab (csv/csv->tab "t" "=SUM(A1:A9),plain")]
    (is (= {:sheets/formula "SUM(A1:A9)"} (m/get-cell tab 1 1)))
    (is (= {:sheets/value "plain"} (m/get-cell tab 1 2)))
    ;; No evaluation happens anywhere in `sheets`, so what is written back is
    ;; the formula and not a value it does not have.
    (is (= "=SUM(A1:A9),plain" (csv/tab->csv tab)))))

(deftest every-value-read-is-text
  ;; CSV does not say what a field is, and guessing is how a part number
  ;; becomes a float.
  (let [tab (csv/csv->tab "t" "1200,0042,3.0e2")]
    (is (= ["1200" "0042" "3.0e2"]
           (mapv #(:sheets/value (m/get-cell tab 1 %)) [1 2 3])))))

(deftest an-empty-field-is-absent-rather-than-empty
  ;; A cell that is not there and a cell holding "" are different things to
  ;; anything counting them, and CSV cannot tell them apart.
  (let [tab (csv/csv->tab "t" "a,,c")]
    (is (= [[1 1] [1 3]] (sort (keys (:sheets/cells tab)))))
    (is (nil? (m/get-cell tab 1 2)))))

(deftest line-endings-are-read-either-way
  (doseq [[label text] {"crlf" "a,b\r\nc,d" "lf" "a,b\nc,d" "cr" "a,b\rc,d"}]
    (testing label
      (is (= [["a" "b"] ["c" "d"]] (csv/parse-csv text))))))

(deftest a-trailing-newline-does-not-add-a-row
  (is (= [["a" "b"]] (csv/parse-csv "a,b\r\n")))
  (is (= [["a" "b"] ["c" "d"]] (csv/parse-csv "a,b\r\nc,d\r\n"))))

(deftest a-truncated-quote-imports-as-far-as-it-goes
  ;; Rather than throwing: a file that was cut off should come in as what it
  ;; has and let the reader see it, which is what a validator is for.
  (is (= [["a" "unterminated"]] (csv/parse-csv "a,\"unterminated"))))

(deftest a-newline-inside-quotes-is-not-a-row-break
  (is (= [["one" "two\r\nlines"] ["next" "row"]]
         (csv/parse-csv "one,\"two\r\nlines\"\r\nnext,row"))))

(deftest importing-twice-leaves-one-tab
  (let [wb (-> (m/workbook "wb")
               (csv/import-csv "data" "a,b")
               (csv/import-csv "data" "c,d"))]
    (is (= ["data"] (keys (:sheets/tabs wb))))
    (is (= {:sheets/value "c"} (m/get-cell (m/tab-by-id wb "data") 1 1)))))

(deftest workbook-export-names-the-tab
  (let [wb (m/add-tab (m/workbook "wb") (plan))]
    (is (= (csv/tab->csv (plan)) (csv/workbook->csv wb "plan")))
    (is (nil? (csv/workbook->csv wb "no-such-tab")))))
