(ns sheets.chart
  "A chart, drawn.

  `sheets.model` has had `add-chart` from the start and nothing could draw
  one or write one — `sheets.xlsx/unexpressed` reported charts as dropped and
  that was the whole of their existence. A chart you cannot see until you
  export it is a poor deal, so this draws it.

  ## SVG, and here rather than in an application

  Portable `.cljc` producing a string, so the same chart can be tested
  without a browser, rendered server-side, and put in a page by anything
  that can put a string in a page. An application drawing its own would be
  the second implementation of an axis.

  ## What a chart is over

  A range of cells, and this asks `sheets.formula` for what they come to
  rather than reading `:sheets/value` — a chart over a column of `=SUM(…)`
  should plot the totals, and reading the stored value would plot nothing at
  all, since a formula cell has none.

  ## What it refuses to draw

  A range with no numbers in it. An empty plot area with axes is a picture
  of a chart, and it reads as *there is no data here* rather than as *this
  is broken*, which is the wrong answer when the range is simply wrong."
  (:require [clojure.string :as str]
            [sheets.formula :as formula]
            [sheets.model :as model]))

(def chart-kinds
  "What can be drawn. `:bar` is the default because a chart with no kind is
  almost always a comparison of magnitudes."
  #{:bar :line :pie})

(defn- parse-range
  "`A1:B9` → `[[r1 c1] [r2 c2]]`, or nil.

  Normalised so that the first corner is the top-left whichever way round it
  was written — `B9:A1` is the same range as `A1:B9` and a chart that drew
  nothing for one of them would be answering a question about typing."
  [range-text]
  (let [[from to] (str/split (str range-text) #":" 2)
        a (formula/parse-ref from)
        b (formula/parse-ref (or to from))]
    (when (and a b)
      [[(min (first a) (first b)) (min (second a) (second b))]
       [(max (first a) (first b)) (max (second a) (second b))]])))

(defn series
  "The numbers a chart is over, with their labels.

  `{:labels [..] :values [..]}`. The first column of the range is taken as
  labels when it holds no numbers — a range selected by dragging across a
  table almost always includes the row headings, and plotting `四半期` as
  zero is worse than using it to name the bar."
  [tab chart]
  (when-let [[[r1 c1] [r2 c2]] (parse-range (:sheets/data-range chart))]
    (let [at (fn [r c] (formula/value-at tab r c))
          number (fn [x] (let [n (formula/as-number x)] (when n n)))
          column (fn [c] (mapv #(at % c) (range r1 (inc r2))))
          first-col (column c1)
          labels? (and (> c2 c1) (not-any? number first-col))
          value-col (if labels? (inc c1) c1)
          values (column value-col)]
      {:labels (if labels? first-col (mapv str (range 1 (inc (count values)))))
       :values (mapv number values)})))

;; ── drawing ─────────────────────────────────────────────────────────────────

(def ^:private palette
  ["#1f6feb" "#e3651d" "#2da44e" "#8250df" "#bf3989" "#9a6700"])

(defn- esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- round2 [x]
  (/ (double (Math/round (* 100.0 (double x)))) 100.0))

(defn- nice-max
  "An axis top that is a round number at or above the largest value.

  A bar chart whose tallest bar touches the frame reads as clipped. This
  rounds up to one, two or five times a power of ten — the steps a person
  would choose — rather than adding a fixed percentage, which produces axis
  labels like 1,127."
  [largest]
  (if (or (nil? largest) (<= largest 0))
    1
    (let [magnitude (Math/pow 10 (Math/floor (/ (Math/log largest) (Math/log 10))))
          scaled (/ largest magnitude)]
      (* magnitude (cond (<= scaled 1) 1 (<= scaled 2) 2 (<= scaled 5) 5 :else 10)))))

(defn- bars [values labels top width height pad]
  (let [n (count values)
        step (/ (- width (* 2 pad)) (max 1 n))
        bar-width (* step 0.7)]
    (apply str
           (map-indexed
            (fn [i v]
              (let [v (or v 0)
                    h (* (- height (* 2 pad)) (/ (max 0.0 (double v)) top))
                    x (+ pad (* i step) (* (- step bar-width) 0.5))
                    y (- height pad h)]
                (str "<rect x=\"" (round2 x) "\" y=\"" (round2 y)
                     "\" width=\"" (round2 bar-width) "\" height=\"" (round2 h)
                     "\" fill=\"" (nth palette (mod i (count palette))) "\">"
                     ;; `format-number`, so the tooltip says 1200 and not
                     ;; 1200.0 — the same text the cell shows.
                     "<title>" (esc (nth labels i (str (inc i)))) ": "
                     (esc (formula/format-number v)) "</title>"
                     "</rect>")))
            values))))

(defn- polyline [values top width height pad]
  (let [n (count values)
        step (if (> n 1) (/ (- width (* 2 pad)) (dec n)) 0)]
    (str "<polyline fill=\"none\" stroke=\"" (first palette) "\" stroke-width=\"2\" points=\""
         (str/join " "
                   (map-indexed
                    (fn [i v]
                      (let [v (or v 0)
                            x (+ pad (* i step))
                            y (- height pad (* (- height (* 2 pad))
                                               (/ (max 0.0 (double v)) top)))]
                        (str (round2 x) "," (round2 y))))
                    values))
         "\"/>")))

(defn- pie [values labels width height]
  (let [total (reduce + 0.0 (map #(max 0.0 (double (or % 0))) values))
        cx (/ width 2.0) cy (/ height 2.0)
        r (* 0.4 (min width height))]
    (if (zero? total)
      ""
      (str/join
       (first
        (reduce
         (fn [[out from] [i v]]
           (let [fraction (/ (max 0.0 (double (or v 0))) total)
                 to (+ from fraction)
                 ;; Two decimals of a turn is a third of a degree, which is
                 ;; below what a 320-pixel pie can show.
                 angle (fn [t] (* 2 Math/PI (- t 0.25)))
                 x (fn [t] (round2 (+ cx (* r (Math/cos (angle t))))))
                 y (fn [t] (round2 (+ cy (* r (Math/sin (angle t))))))
                 large (if (> fraction 0.5) 1 0)]
             [(conj out
                    (if (>= fraction 1)
                      ;; A single slice is a circle: an arc from a point
                      ;; back to itself draws nothing.
                      (str "<circle cx=\"" (round2 cx) "\" cy=\"" (round2 cy)
                           "\" r=\"" (round2 r) "\" fill=\"" (first palette) "\">"
                           "<title>" (esc (nth labels i (str (inc i)))) "</title></circle>")
                      (str "<path d=\"M" (round2 cx) "," (round2 cy)
                           " L" (x from) "," (y from)
                           " A" (round2 r) "," (round2 r) " 0 " large ",1 "
                           (x to) "," (y to) " Z\" fill=\""
                           (nth palette (mod i (count palette))) "\">"
                           "<title>" (esc (nth labels i (str (inc i)))) ": "
                           (esc (formula/format-number (or v 0)))
                           "</title></path>")))
              to]))
         [[] 0.0]
         (map-indexed vector values)))))))

(defn svg
  "One chart as an SVG string, or nil when there is nothing to draw.

  Nil rather than an empty frame: axes around no data read as *there is no
  data here*, which is the wrong answer when the range is simply wrong. A
  caller that gets nil can say which."
  ([tab chart] (svg tab chart {}))
  ([tab chart {:keys [width height] :or {width 320 height 200}}]
   (let [{:keys [labels values]} (series tab chart)
         numbers (keep identity values)]
     (when (seq numbers)
       (let [kind (or (:sheets/chart-type chart) :bar)
             kind (if (contains? chart-kinds kind) kind :bar)
             pad 24
             top (nice-max (apply max numbers))]
         (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
              width " " height "\" role=\"img\" aria-label=\""
              (esc (or (:sheets/title chart) (:sheets/id chart) "グラフ")) "\">"
              (when-not (= :pie kind)
                ;; Two lines, not a grid: the baseline and the axis. A grid
                ;; behind three bars is more chart than data.
                (str "<line x1=\"" pad "\" y1=\"" (- height pad) "\" x2=\"" (- width pad)
                     "\" y2=\"" (- height pad) "\" stroke=\"#8c959f\"/>"
                     "<line x1=\"" pad "\" y1=\"" pad "\" x2=\"" pad
                     "\" y2=\"" (- height pad) "\" stroke=\"#8c959f\"/>"
                     "<text x=\"" (- pad 4) "\" y=\"" (+ pad 4)
                     "\" text-anchor=\"end\" font-size=\"10\" fill=\"#57606a\">"
                     (esc (formula/format-number top)) "</text>"))
              (case kind
                :bar (bars values labels top width height pad)
                :line (polyline values top width height pad)
                :pie (pie values labels width height))
              "</svg>"))))))

(defn charts-of
  "Every chart in `workbook` that is over `tab-id`, with its SVG.

  `{:id :title :svg}` per chart, and a chart whose range holds no numbers
  appears with `:svg` nil rather than being left out — a chart somebody
  defined and cannot see is a thing to say, not a thing to hide."
  [workbook tab-id]
  (let [tab (model/tab-by-id workbook tab-id)]
    (->> (:sheets/charts workbook)
         (filter #(or (nil? (:sheets/tab %)) (= tab-id (:sheets/tab %))))
         (mapv (fn [chart]
                 {:id (:sheets/id chart)
                  :title (:sheets/title chart)
                  :svg (when tab (svg tab chart))})))))
