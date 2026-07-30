(ns sheets.formula-test
  (:require [clojure.test :refer [deftest is]]
            [sheets.formula :as f]
            [sheets.model :as m]))

(defn- sheet
  "A tab with amounts in column B and labels in column A."
  [& formulas]
  (reduce (fn [tab [row expr]] (m/put-formula tab row 2 expr))
          (-> (m/tab "t" {:sheets/title "t"})
              (m/put-cell 1 1 "四半期") (m/put-cell 1 2 "金額")
              (m/put-cell 2 1 "Q1") (m/put-cell 2 2 "1200")
              (m/put-cell 3 1 "Q2") (m/put-cell 3 2 "1300"))
          (partition 2 formulas)))

(defn- at [tab row] (f/value-at tab row 2))
(defn- one [expr] (at (sheet 9 expr) 9))

(deftest arithmetic-and-references
  (is (= "2500" (one "B2+B3")))
  (is (= "100" (one "B3-1200")))
  (is (= "2400" (one "B2*2")))
  (is (= "600" (one "B2/2")))
  (is (= "8" (one "2^3")))
  (is (= "2500" (one "B2*2+100")) "times binds tighter than plus")
  (is (= "3600" (one "B2*(2+1)")) "and parentheses win")
  (is (= "-1200" (one "-B2")))
  (is (= "1200" (one "$B$2")) "absolute addressing is accepted and ignored"))

(deftest aggregates-take-the-numbers-there-are
  ;; A column of amounts with a heading on top sums to the amounts. Excel
  ;; does this and the alternative — refusing because B1 says 金額 — would
  ;; make SUM unusable on any real sheet.
  (is (= "2500" (one "SUM(B1:B3)")))
  (is (= "2500" (one "SUM(B2:B3)")))
  (is (= "1250" (one "AVERAGE(B2:B3)")))
  (is (= "2" (one "COUNT(B1:B3)")) "COUNT counts numbers")
  (is (= "3" (one "COUNTA(B1:B3)")) "COUNTA counts anything there")
  (is (= "1200" (one "MIN(B2:B3)")))
  (is (= "1300" (one "MAX(B2:B3)")))
  (is (= "5" (one "SUM(2,3)")) "arguments, not only ranges")
  (is (= "2500" (one "SUM(B2,B3)"))))

(deftest arithmetic-asks-for-a-number-and-says-when-there-is-none
  ;; The difference from an aggregate: `=A2+1` asks for a number and A2 is
  ;; the text "Q1". Excel答えは #VALUE!, and inventing 0 would be the guess
  ;; `sheets.csv` refuses arriving from the other side.
  (is (= "#VALUE!" (one "A2+1")))
  (is (= "#VALUE!" (one "A2*2")))
  ;; And an empty cell in arithmetic is not zero either.
  (is (= "#VALUE!" (one "Z9+1"))))

(deftest errors-are-values-and-they-propagate
  (is (= "#DIV/0!" (one "B2/0")))
  (is (= "#NAME?" (one "NOPE(1)")))
  (is (= "#NAME?" (one "1+")) "an unreadable formula is a value, not a throw")
  ;; A sum over a range containing an error is that error — a total that
  ;; silently omitted it would be a number nobody can see is wrong.
  (let [tab (sheet 4 "B2/0" 5 "SUM(B2:B4)")]
    (is (= "#DIV/0!" (at tab 4)))
    (is (= "#DIV/0!" (at tab 5)))))

(deftest a-cell-that-refers-to-itself-says-so
  ;; Rather than overflowing the stack. A spreadsheet that crashes on a
  ;; self-reference is worse than one that reports it.
  (is (= "#CIRCULAR!" (at (sheet 9 "B9") 9)))
  ;; Round trip through another cell, which is the case a naive depth limit
  ;; would miss.
  (let [tab (sheet 8 "B9" 9 "B8")]
    (is (= "#CIRCULAR!" (at tab 8)))
    (is (= "#CIRCULAR!" (at tab 9))))
  ;; Three deep.
  (let [tab (sheet 7 "B8" 8 "B9" 9 "B7")]
    (is (= "#CIRCULAR!" (at tab 7)))))

(deftest referring-to-the-same-cell-twice-is-not-a-cycle
  ;; The mistake a naive "seen" set makes: B4 and B5 both read B2, and B6
  ;; reads both. Nothing here is circular.
  (let [tab (sheet 4 "B2*1" 5 "B2*2" 6 "B4+B5")]
    (is (= "3600" (at tab 6)))))

(deftest comparison-and-if
  (is (= "多い" (one "IF(B2>1000,\"多い\",\"少ない\")")))
  (is (= "少ない" (one "IF(B2>2000,\"多い\",\"少ない\")")))
  (is (= "TRUE" (one "B2<B3")))
  (is (= "FALSE" (one "B2=B3")))
  (is (= "TRUE" (one "B2<>B3")))
  (is (= "TRUE" (one "B2<=1200")))
  ;; Text compares as text, which is what a spreadsheet does.
  (is (= "TRUE" (one "A2=\"Q1\""))))

(deftest text-joins-with-ampersand
  (is (= "Q1-1200" (one "A2&\"-\"&B2")))
  (is (= "a\"b" (one "\"a\"\"b\"")) "two quotes inside a literal are one"))

(deftest a-number-comes-out-as-a-number-and-not-as-a-double
  ;; `120.0` in a cell is a number wearing floating point's clothes.
  (is (= "2500" (one "SUM(B2:B3)")))
  (is (= "1250.5" (one "2501/2")))
  (is (= "1250.57" (one "ROUND(1250.567,2)")))
  (is (= "1251" (one "ROUND(1250.567,0)")))
  (is (= "1200" (one "ABS(-B2)"))))

(deftest nothing-is-written-back
  ;; A computed value stored in :sheets/value would be a second copy of
  ;; something derived — stale the moment an input changes, and afterwards
  ;; indistinguishable from a value somebody typed.
  (let [tab (sheet 4 "SUM(B2:B3)")]
    (is (= "2500" (at tab 4)))
    (is (nil? (:sheets/value (m/get-cell tab 4 2))))
    (is (= "SUM(B2:B3)" (:sheets/formula (m/get-cell tab 4 2))))
    ;; And changing an input changes the answer, with nothing to invalidate.
    (let [tab (m/put-cell tab 2 2 "2000")]
      (is (= "3300" (at tab 4))))))

(deftest values-answers-for-every-cell
  (let [tab (sheet 4 "SUM(B2:B3)")
        vs (f/values tab)]
    (is (= "2500" (get vs [4 2])))
    (is (= "1200" (get vs [2 2])))
    (is (= "四半期" (get vs [1 1])))))

(deftest nonsense-does-not-throw
  ;; Formulas arrive from a person typing into a grid.
  (doseq [expr ["" "=" "((((" ")))" "SUM(" "SUM()" "1..2" "@@@" "A" "1:2"
                "SUM(B2:B3" "\"unterminated" "B2 B3" "----1"]]
    (is (string? (one expr)) (pr-str expr))))
