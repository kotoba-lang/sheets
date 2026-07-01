(ns sheets.model)

(defn workbook
  ([id] (workbook id {}))
  ([id attrs]
   (merge {:sheets/id id
           :sheets/type :workbook
           :sheets/tabs {}
           :sheets/named-ranges {}
           :sheets/charts []}
          attrs)))

(defn tab
  ([id] (tab id {}))
  ([id attrs]
   (merge {:sheets/id id
           :sheets/title id
           :sheets/cells {}}
          attrs)))

(defn cell-key [row col]
  [(long row) (long col)])

(defn put-cell [tab row col value]
  (assoc-in tab [:sheets/cells (cell-key row col)] {:sheets/value value}))

(defn put-formula [tab row col expr]
  (assoc-in tab [:sheets/cells (cell-key row col)] {:sheets/formula expr}))

(defn put-cell-style [tab row col style]
  (assoc-in tab [:sheets/cells (cell-key row col) :sheets/style] style))

(defn get-cell [tab row col]
  (get-in tab [:sheets/cells (cell-key row col)]))

(defn add-tab [wb t]
  (assoc-in wb [:sheets/tabs (:sheets/id t)] t))

(defn add-named-range [wb id attrs]
  (assoc-in wb [:sheets/named-ranges id]
            (merge {:sheets/id id} attrs)))

(defn add-chart [wb chart]
  (update wb :sheets/charts conj chart))

(defn tab-by-id [wb id]
  (get-in wb [:sheets/tabs id]))

(defn range-values [tab row1 col1 row2 col2]
  (vec
   (for [row (range row1 (inc row2))]
     (vec
      (for [col (range col1 (inc col2))]
        (:sheets/value (get-cell tab row col)))))))

(defn seed-workbook []
  (-> (workbook "gftd-sheets")
      (add-tab (-> (tab "plan" {:sheets/title "Plan"})
                   (put-cell 1 1 "Workstream")
                   (put-cell 1 2 "Owner")
                   (put-cell 2 1 "Slides")
                   (put-cell 2 2 "GFTD")))))
