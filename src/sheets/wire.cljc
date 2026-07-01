(ns sheets.wire
  "Transit wire helpers for Kotoba Sheets workbooks."
  (:require [transit.core :as transit]))

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
