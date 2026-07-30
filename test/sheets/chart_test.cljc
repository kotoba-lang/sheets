(ns sheets.chart-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sheets.chart :as chart]
            [sheets.model :as m]))

(defn- quarters []
  (-> (m/tab "t" {:sheets/title "売上"})
      (m/put-cell 1 1 "Q1") (m/put-cell 1 2 "1200")
      (m/put-cell 2 1 "Q2") (m/put-cell 2 2 "800")
      (m/put-cell 3 1 "Q3") (m/put-cell 3 2 "1500")))

(defn- draw
  ([range'] (draw range' :bar))
  ([range' kind]
   (chart/svg (quarters) {:sheets/id "c" :sheets/data-range range'
                          :sheets/chart-type kind})))

(deftest a-range-of-headings-and-numbers-becomes-labels-and-values
  ;; A range selected by dragging across a table almost always includes the
  ;; row headings, and plotting 四半期 as zero is worse than using it to name
  ;; the bar.
  (is (= {:labels ["Q1" "Q2" "Q3"] :values [1200.0 800.0 1500.0]}
         (chart/series (quarters) {:sheets/data-range "A1:B3"})))
  ;; A single column of numbers has no headings, so the labels are positions.
  (is (= {:labels ["1" "2" "3"] :values [1200.0 800.0 1500.0]}
         (chart/series (quarters) {:sheets/data-range "B1:B3"}))))

(deftest a-chart-plots-what-a-formula-comes-to
  ;; Reading `:sheets/value` would plot nothing at all, because a formula
  ;; cell has none.
  (let [tab (-> (quarters) (m/put-formula 4 1 "\"合計\"")
                (m/put-formula 4 2 "SUM(B1:B3)"))]
    (is (= [1200.0 800.0 1500.0 3500.0]
           (:values (chart/series tab {:sheets/data-range "A1:B4"}))))))

(deftest a-range-written-backwards-is-the-same-range
  ;; `B3:A1` is what dragging up and left produces, and a chart that drew
  ;; nothing for it would be answering a question about typing.
  (is (= (chart/series (quarters) {:sheets/data-range "A1:B3"})
         (chart/series (quarters) {:sheets/data-range "B3:A1"}))))

(deftest a-range-with-no-numbers-draws-nothing
  ;; Nil rather than an empty frame: axes around no data read as "there is
  ;; no data here", which is the wrong answer when the range is simply
  ;; wrong. A caller that gets nil can say which.
  (is (nil? (draw "A1:A3")))
  (is (nil? (draw "Z1:Z9")))
  (is (nil? (draw "not a range")))
  (is (nil? (draw ""))))

(deftest every-kind-draws-and-an-unknown-one-is-a-bar
  (is (str/includes? (draw "A1:B3" :bar) "<rect"))
  (is (str/includes? (draw "A1:B3" :line) "<polyline"))
  (is (str/includes? (draw "A1:B3" :pie) "<path"))
  ;; A chart with no kind is almost always a comparison of magnitudes.
  (is (str/includes? (draw "A1:B3" nil) "<rect"))
  (is (str/includes? (draw "A1:B3" :sunburst) "<rect")))

(deftest the-axis-top-is-a-round-number
  ;; A bar chart whose tallest bar touches the frame reads as clipped, and
  ;; adding a fixed percentage produces axis labels like 1,127.
  (is (str/includes? (draw "A1:B3") ">2000<"))
  (let [tab (-> (m/tab "t" {}) (m/put-cell 1 1 "7"))]
    (is (str/includes? (chart/svg tab {:sheets/id "c" :sheets/data-range "A1:A1"})
                       ">10<"))))

(deftest a-value-is-shown-the-way-the-cell-shows-it
  ;; 1200, not 1200.0 — the same text `format-number` gives the cell.
  (is (str/includes? (draw "A1:B3") "<title>Q1: 1200</title>"))
  (is (not (str/includes? (draw "A1:B3") "1200.0"))))

(deftest one-slice-is-a-circle
  ;; An arc from a point back to itself draws nothing, so a pie of one value
  ;; would be blank.
  (let [tab (-> (m/tab "t" {}) (m/put-cell 1 1 "5"))]
    (is (str/includes? (chart/svg tab {:sheets/id "c" :sheets/data-range "A1:A1"
                                       :sheets/chart-type :pie})
                       "<circle"))))

(deftest the-svg-is-labelled-for-a-reader-who-cannot-see-it
  (is (str/includes? (chart/svg (quarters) {:sheets/id "c" :sheets/title "四半期売上"
                                            :sheets/data-range "A1:B3"})
                     "aria-label=\"四半期売上\""))
  (is (str/includes? (draw "A1:B3") "role=\"img\"")))

(deftest text-in-a-label-is-escaped
  (let [tab (-> (m/tab "t" {}) (m/put-cell 1 1 "<script>") (m/put-cell 1 2 "5"))]
    (is (str/includes? (chart/svg tab {:sheets/id "c" :sheets/data-range "A1:B1"})
                       "&lt;script&gt;"))))

(deftest charts-of-says-which-ones-cannot-be-drawn
  ;; A chart somebody defined and cannot see is a thing to say, not a thing
  ;; to hide.
  (let [wb (-> (m/workbook "wb")
               (m/add-tab (quarters))
               (m/add-chart {:sheets/id "good" :sheets/data-range "A1:B3"})
               (m/add-chart {:sheets/id "empty" :sheets/data-range "Z1:Z9"}))
        drawn (chart/charts-of wb "t")]
    (is (= ["good" "empty"] (mapv :id drawn)))
    (is (some? (:svg (first drawn))))
    (is (nil? (:svg (second drawn))))))

(deftest drawing-does-not-throw-on-a-half-built-chart
  (doseq [c [{} {:sheets/data-range nil} {:sheets/data-range 42}
             {:sheets/data-range "A1"} {:sheets/data-range "A1:"}]]
    (is (nil? (chart/svg (m/tab "t" {}) c)) (pr-str c))))
