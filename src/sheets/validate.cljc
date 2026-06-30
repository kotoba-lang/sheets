(ns sheets.validate)

(defn problem [severity code id msg]
  {:sheets/severity severity :sheets/code code :sheets/id id :sheets/msg msg})

(defn cell-problems [tab]
  (for [[[row col] cell] (:sheets/cells tab)
        :when (or (< row 1) (< col 1)
                  (and (contains? cell :sheets/value)
                       (contains? cell :sheets/formula)))]
    (problem :error :cell/invalid [(:sheets/id tab) row col] "cell address or payload is invalid")))

(defn problems [wb]
  (vec (mapcat cell-problems (vals (:sheets/tabs wb)))))

(defn valid? [wb]
  (not-any? #(= :error (:sheets/severity %)) (problems wb)))
