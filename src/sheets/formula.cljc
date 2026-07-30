(ns sheets.formula
  "What `=SUM(B2:B9)` comes to.

  A workbook here could hold a formula and never compute one. `sheets.xlsx`
  writes `<f>` with no cached value on purpose — Excel recalculates on open,
  which is correct — but inside this Drive a spreadsheet showed the text
  `=SUM(B2:B9)` for ever. Computing is the whole point of a spreadsheet.

  ## Nothing is stored

  `values` takes a tab and returns what each cell comes to *now*. A computed
  value written back into `:sheets/value` would be a second copy of something
  derived, stale the moment any input changed, and indistinguishable
  afterwards from a value somebody typed. The model holds formulas; this
  answers what they mean.

  ## Cells hold text, and that is not undone here

  `sheets.csv` reads every field as a string, and `sheets.xlsx` writes every
  cell as one, both for the same stated reason: guessing is how a part number
  becomes a float. So evaluation parses text to a number *where a number is
  required*, and says so when it cannot — it does not decide that `0042` was
  a number all along.

  Which follows Excel: `=A1+1` over text is `#VALUE!`, while `=SUM(A1:A9)`
  ignores the text in the range. The difference is that arithmetic asks for
  a number and an aggregate asks for the numbers there are.

  ## Errors are values

  `#DIV/0!`, `#VALUE!`, `#NAME?`, `#REF!`, `#CIRCULAR!` — a cell whose
  formula cannot be computed holds the reason, which is what a spreadsheet
  does and is more useful than an empty cell or a thrown exception. They
  propagate: a sum over a range containing `#DIV/0!` is `#DIV/0!`, because
  the alternative is a total that silently omits a number nobody can see is
  missing."
  (:require [clojure.string :as str]
            [sheets.model :as model]))

(def error-values
  "The strings a cell holds when its formula cannot be computed."
  #{"#DIV/0!" "#VALUE!" "#NAME?" "#REF!" "#CIRCULAR!"})

(defn error? [x] (contains? error-values (str x)))

;; ── reading a formula ───────────────────────────────────────────────────────

(defn- number-token [s i]
  (let [n (count s)]
    (loop [j i seen-dot? false]
      (cond
        (>= j n) [(subs s i j) j]
        (= \. (nth s j)) (if seen-dot? [(subs s i j) j] (recur (inc j) true))
        (contains? (set "0123456789") (nth s j)) (recur (inc j) seen-dot?)
        :else [(subs s i j) j]))))

(defn- word-token
  "A run of letters, digits, `$` and `_` — a cell reference or a function
  name, which cannot be told apart until something follows them."
  [s i]
  (let [n (count s)
        word? (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789$_")]
    (loop [j i]
      (if (and (< j n) (contains? word? (nth s j)))
        (recur (inc j))
        [(subs s i j) j]))))

(defn- string-token
  "A double-quoted literal. `\"\"` inside one is a literal quote, which is how
  a spreadsheet spells it and not how most languages do."
  [s i]
  (let [n (count s)]
    (loop [j (inc i) out []]
      (cond
        (>= j n) [(apply str out) j]
        (= \" (nth s j)) (if (and (< (inc j) n) (= \" (nth s (inc j))))
                           (recur (+ j 2) (conj out \"))
                           [(apply str out) (inc j)])
        :else (recur (inc j) (conj out (nth s j)))))))

(defn tokens
  "A formula as tokens. Never throws — an unreadable one becomes tokens the
  parser refuses, which is a `#NAME?` in a cell rather than a 500."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [c (nth s i)]
          (cond
            (contains? (set " \t\n") c) (recur (inc i) out)
            (contains? (set "0123456789") c)
            (let [[t j] (number-token s i)] (recur j (conj out [:number t])))
            (= \" c) (let [[t j] (string-token s i)] (recur j (conj out [:string t])))
            (contains? (set "+-*/^%(),:") c)
            (recur (inc i) (conj out [(get {\( :lparen \) :rparen \, :comma
                                            \: :colon}
                                           c :op)
                                      (str c)]))
            (and (= \< c) (< (inc i) n) (= \= (nth s (inc i))))
            (recur (+ i 2) (conj out [:op "<="]))
            (and (= \< c) (< (inc i) n) (= \> (nth s (inc i))))
            (recur (+ i 2) (conj out [:op "<>"]))
            (and (= \> c) (< (inc i) n) (= \= (nth s (inc i))))
            (recur (+ i 2) (conj out [:op ">="]))
            (contains? (set "<>=") c) (recur (inc i) (conj out [:op (str c)]))
            (= \& c) (recur (inc i) (conj out [:op "&"]))
            :else (let [[t j] (word-token s i)]
                    (if (= i j)
                      ;; A character nothing knows. Consumed rather than
                      ;; looped on for ever.
                      (recur (inc i) (conj out [:unknown (str c)]))
                      (recur j (conj out [:word t]))))))))))

(defn parse-ref
  "`B12` or `$B$12` → `[row col]`, or nil.

  `$` is absolute addressing, which matters when a formula is copied and
  nothing here copies formulas — so it is accepted and ignored rather than
  refused, because refusing it would reject a file Excel wrote."
  [word]
  (when-let [[_ letters digits]
             (re-matches #"\$?([A-Za-z]{1,3})\$?(\d{1,7})" (str word))]
    (when-let [col (model/column-number letters)]
      [#?(:clj (Long/parseLong digits) :cljs (js/parseInt digits 10)) col])))

;; ── the grammar ─────────────────────────────────────────────────────────────
;;
;; Precedence, loosest first: comparison, `&`, `+ -`, `* /`, `^`, unary `-`,
;; then a primary. Recursive descent, one function per level, which is the
;; shape the grammar already has.

(declare parse-expr)

(defn- parse-args [ts]
  (loop [ts ts args []]
    (if (or (empty? ts) (= :rparen (first (first ts))))
      [args (rest ts)]
      (let [[a ts] (parse-expr ts)
            args (conj args a)]
        (if (= :comma (first (first ts)))
          (recur (rest ts) args)
          [args (rest ts)])))))

(defn- parse-primary [ts]
  (let [[kind text] (first ts)]
    (cond
      (nil? kind) [[:error "#NAME?"] nil]
      (= :number kind) [[:num text] (rest ts)]
      (= :string kind) [[:str text] (rest ts)]
      (and (= :op kind) (= "-" text))
      (let [[e ts] (parse-primary (rest ts))] [[:neg e] ts])
      (and (= :op kind) (= "+" text)) (parse-primary (rest ts))

      (= :lparen kind)
      (let [[e ts] (parse-expr (rest ts))]
        [e (if (= :rparen (first (first ts))) (rest ts) ts)])

      (= :word kind)
      (cond
        ;; A name followed by `(` is a call; otherwise it is a reference,
        ;; and a reference that is not one is `#NAME?`.
        (= :lparen (first (first (rest ts))))
        (let [[args ts] (parse-args (rest (rest ts)))]
          [[:call (str/upper-case text) args] ts])

        ;; `A1:B9` — a range, which only means anything inside a call.
        (= :colon (first (first (rest ts))))
        [[:range text (second (first (rest (rest ts))))] (rest (rest (rest ts)))]

        (parse-ref text) [[:ref text] (rest ts)]
        :else [[:error "#NAME?"] (rest ts)])

      :else [[:error "#NAME?"] (rest ts)])))

(defn- parse-binary [ts levels]
  (if (empty? levels)
    (parse-primary ts)
    (let [ops (first levels)]
      (loop [[left ts] (parse-binary ts (rest levels))]
        (let [[kind text] (first ts)
              op (when (= :op kind) text)]
          (if (and op (contains? ops op))
            (let [[right ts] (parse-binary (rest ts) (rest levels))]
              (recur [[:op op left right] ts]))
            [left ts]))))))

(def ^:private precedence
  [#{"=" "<>" "<" ">" "<=" ">="} #{"&"} #{"+" "-"} #{"*" "/"} #{"^"}])

(defn- parse-expr [ts] (parse-binary ts precedence))

(defn parse
  "A formula's text as a tree. Never throws."
  [text]
  (let [text (str text)
        text (if (str/starts-with? text "=") (subs text 1) text)]
    (first (parse-expr (tokens text)))))

;; ── computing ───────────────────────────────────────────────────────────────

(defn- as-number
  "Text as a number, or nil. The one place a string becomes one, and only
  because something asked for a number."
  [x]
  (cond
    (number? x) x
    (nil? x) nil
    :else (let [s (str/trim (str x))]
            (when-not (str/blank? s)
              #?(:clj (try (Double/parseDouble s) (catch Exception _ nil))
                 :cljs (let [n (js/parseFloat s)]
                         (when (and (not (js/isNaN n))
                                    (re-matches #"[-+]?[0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?" s))
                           n)))))))

(defn format-number
  "A number as the text a cell holds.

  An integer without a decimal point, because `120.0` in a cell is a number
  wearing floating point's clothes. Anything else to ten significant places
  and no trailing zeros, which is enough to not surprise and not enough to
  show the noise at the end of a double."
  [n]
  (let [d (double n)]
    (cond
      ;; `(not= d d)` is the portable NaN test — it is the only value that is
      ;; not equal to itself, and `Double/isNaN` exists on one host only.
      (not= d d) "#VALUE!"
      #?(:clj (Double/isInfinite d) :cljs (not (js/isFinite d))) "#VALUE!"
      (zero? (mod d 1)) (str (long d))
      :else (-> #?(:clj (format "%.10f" d) :cljs (.toFixed d 10))
                (str/replace #"0+$" "")
                (str/replace #"\.$" "")))))

(declare value-at)

(defn- numbers-in
  "Every number in a list of evaluated arguments, ranges flattened.

  Text is skipped rather than refused, which is what an aggregate does: a
  column of amounts with a heading on top sums to the amounts."
  [xs]
  (keep as-number (flatten xs)))

(defn- apply-fn [name args]
  (let [nums (numbers-in args)
        flat (flatten args)]
    (cond
      (some error? flat) (first (filter error? flat))
      :else
      (case name
        "SUM" (reduce + 0 nums)
        "AVERAGE" (if (seq nums) (/ (reduce + 0.0 nums) (count nums)) "#DIV/0!")
        "COUNT" (count nums)
        "COUNTA" (count (remove #(or (nil? %) (= "" %)) flat))
        "MIN" (if (seq nums) (reduce min nums) 0)
        "MAX" (if (seq nums) (reduce max nums) 0)
        "ABS" (if-let [n (as-number (first flat))] (Math/abs (double n)) "#VALUE!")
        "ROUND" (let [n (as-number (first flat))
                      places (or (as-number (second flat)) 0)]
                  (if n
                    (let [f (Math/pow 10 places)]
                      ;; `Math/round` rather than `rint`: JS has no rint, and
                      ;; the half-up rounding a spreadsheet does is round's.
                      (/ (double (Math/round (* (double n) f))) f))
                    "#VALUE!"))
        "IF" (let [test (first flat)]
               (if (or (true? test) (and (as-number test) (not (zero? (as-number test)))))
                 (nth flat 1 "")
                 (nth flat 2 "")))
        "#NAME?"))))

(defn- compare-op [op a b]
  (let [na (as-number a) nb (as-number b)
        [x y] (if (and na nb) [na nb] [(str a) (str b)])
        c (compare x y)]
    (case op
      "=" (zero? c) "<>" (not (zero? c))
      "<" (neg? c) ">" (pos? c)
      "<=" (not (pos? c)) ">=" (not (neg? c))
      false)))

(defn- eval-node [node tab seen]
  (let [[kind a b] node]
    (case kind
      :num (or (as-number a) "#VALUE!")
      :str a
      :error a
      :neg (let [v (eval-node a tab seen)]
             (cond (error? v) v
                   (as-number v) (- (as-number v))
                   :else "#VALUE!"))
      :ref (let [[row col] (parse-ref a)]
             (if row (value-at tab row col seen) "#REF!"))
      :range (let [from (parse-ref a) to (parse-ref b)]
               (if (and from to)
                 (vec (for [row (range (min (first from) (first to))
                                       (inc (max (first from) (first to))))
                            col (range (min (second from) (second to))
                                       (inc (max (second from) (second to))))]
                        (value-at tab row col seen)))
                 "#REF!"))
      :call (apply-fn a (mapv #(eval-node % tab seen) b))
      :op (let [op a
                x (eval-node b tab seen)
                y (eval-node (nth node 3) tab seen)]
            (cond
              (error? x) x
              (error? y) y
              :else
              (cond
                  (contains? #{"=" "<>" "<" ">" "<=" ">="} op) (compare-op op x y)
                  (= "&" op) (str x y)
                  :else
                  (let [nx (as-number x) ny (as-number y)]
                    (if-not (and nx ny)
                      "#VALUE!"
                      (case op
                        "+" (+ nx ny) "-" (- nx ny) "*" (* nx ny)
                        "/" (if (zero? ny) "#DIV/0!" (/ (double nx) ny))
                        "^" (Math/pow nx ny)
                        "#VALUE!"))))))
      "#NAME?")))

(defn value-at
  "What the cell at `row`,`col` comes to.

  `seen` is the chain of cells being computed. A cell that appears in its own
  chain is `#CIRCULAR!` — reported rather than recursed into, because the
  alternative is a stack overflow, and a spreadsheet that crashes on a
  self-reference is worse than one that says so."
  ([tab row col] (value-at tab row col #{}))
  ([tab row col seen]
   (let [key [row col]
         cell (get-in tab [:sheets/cells key])]
     (cond
       (contains? seen key) "#CIRCULAR!"
       (contains? cell :sheets/formula)
       (let [v (eval-node (parse (:sheets/formula cell)) tab (conj seen key))]
         (cond (error? v) v
               (number? v) (format-number v)
               (boolean? v) (if v "TRUE" "FALSE")
               (nil? v) ""
               :else (str v)))
       :else (or (:sheets/value cell) "")))))

(defn values
  "Every cell of `tab` as what it comes to, keyed the same way the cells are.

  Computed on demand and never written back: a stored result is a second
  copy of something derived, stale the moment an input changes and
  indistinguishable afterwards from a value somebody typed."
  [tab]
  (into {}
        (map (fn [[key _]] [key (value-at tab (first key) (second key))]))
        (:sheets/cells tab)))
