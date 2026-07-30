(ns sheets.wire
  "Transit wire helpers for Kotoba Sheets workbooks.

  ## Out is lossy, back is explicit

  `transit.core/write-json` is a projection onto plain JSON: keywords become
  bare strings, and every map key becomes a string — including the ones that
  were not strings to begin with. A workbook has two of those, and they are
  the whole reason this namespace needs a reader that knows the schema:

  - a cell address is `[1 1]`, and it comes back as the string `\"[1 1]\"`
  - `:sheets/type` is `:workbook`, and it comes back as `\"workbook\"`

  while a tab id, a named-range id and a cell's style keys are *supposed* to
  stay as they are. No generic keywordizer can tell those apart, which is
  why `rehydrate-workbook` lives here, next to the model that defines them,
  rather than in `transit` — the wire layer does not know what a workbook is
  and should not have to.

  `read-workbook-envelope` gives back the projection unchanged, for callers
  that only want to look at a value. `rehydrate-workbook` turns it back into
  a workbook the model and `sheets.validate` will accept."
  (:require [clojure.string :as str]
            [transit.core :as transit]))

(defn workbook-envelope
  ([workbook] (workbook-envelope workbook {}))
  ([workbook opts]
   (transit/office-envelope :sheets/workbook workbook opts)))

(defn read-workbook-envelope [body]
  (let [envelope (transit/read-office-envelope-body body)]
    (when-not (= :sheets/workbook (:kotoba.resource/kind envelope))
      (throw (ex-info "not a Sheets workbook Transit envelope"
                      {:kind (:kotoba.resource/kind envelope)})))
    (:kotoba.resource/payload envelope)))

;; ── back from plain JSON ────────────────────────────────────────────────────

(defn- parse-int [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn cell-address
  "`\"[1 1]\"` back to `[1 1]`.

  Anything that does not look like one is returned untouched: a workbook
  that arrived from somewhere else may have string cell keys, and dropping
  them would be worse than carrying them."
  [k]
  (if-let [[_ row col] (re-matches #"\[(-?\d+) (-?\d+)\]" (str k))]
    [(parse-int row) (parse-int col)]
    k))

(defn cell-address-string
  "`[1 1]` to the `\"[1 1]\"` the wire produces.

  Exposed because a caller reaching into a projected payload — an editor
  does — otherwise has to guess the format, and guessing wrong is a lookup
  that silently returns nil."
  [address]
  (if (sequential? address)
    (str "[" (str/join " " address) "]")
    (str address)))

(defn- keywordize [m]
  (reduce-kv (fn [acc k v]
               (assoc acc (keyword k) (if (map? v) (keywordize v) v)))
             {} m))

(defn- rehydrate-cell [cell]
  (reduce-kv (fn [acc k v]
               (if (= "sheets/style" k)
                 ;; A style's keys are the author's, not the schema's, so
                 ;; they are keywordized wholesale — which is exactly what
                 ;; must not happen one level up, where the keys are ids.
                 (assoc acc :sheets/style (keywordize v))
                 (assoc acc (keyword k) v)))
             {} cell))

(defn- rehydrate-tab [tab]
  (reduce-kv (fn [acc k v]
               (if (= "sheets/cells" k)
                 (assoc acc :sheets/cells
                        (reduce-kv (fn [cells address cell]
                                     (assoc cells (cell-address address)
                                            (rehydrate-cell cell)))
                                   {} v))
                 (assoc acc (keyword k) v)))
             {} tab))

(defn- rehydrate-chart [chart]
  (reduce-kv (fn [acc k v]
               (assoc acc (keyword k) (if (= "sheets/type" k) (keyword v) v)))
             {} chart))

(defn rehydrate-workbook
  "A plain-JSON payload back into a workbook.

  The inverse of what `office-envelope` did on the way out, as far as it can
  be: a value that was a keyword becomes one again because the schema says
  which ones were, and an id that was a string stays a string for the same
  reason."
  [payload]
  (reduce-kv
   (fn [acc k v]
     (case k
       "sheets/type" (assoc acc :sheets/type (keyword v))
       "sheets/tabs" (assoc acc :sheets/tabs
                            (reduce-kv (fn [tabs id tab]
                                         (assoc tabs id (rehydrate-tab tab)))
                                       {} v))
       "sheets/named-ranges" (assoc acc :sheets/named-ranges
                                    (reduce-kv (fn [ranges id range]
                                                 (assoc ranges id (keywordize range)))
                                               {} v))
       "sheets/charts" (assoc acc :sheets/charts (mapv rehydrate-chart v))
       (assoc acc (keyword k) v)))
   {} payload))

(defn workbook-of-envelope
  "Read an envelope body and rehydrate it in one step."
  [body]
  (rehydrate-workbook (read-workbook-envelope body)))
