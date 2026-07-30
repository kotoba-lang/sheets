(ns sheets.xlsx-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ooxml.core :as ooxml]
            [sheets.model :as m]
            [sheets.xlsx :as xlsx]))

(defn- plan []
  (-> (m/workbook "wb" {:sheets/title "計画"})
      (m/add-tab (-> (m/tab "plan" {:sheets/title "Plan"})
                     (m/put-cell 1 1 "Quarter")
                     (m/put-cell 1 2 "Revenue")
                     (m/put-cell 2 1 "Q1")
                     (m/put-cell 2 2 "1200")
                     (m/put-formula 3 2 "SUM(B2:B2)")))))

(deftest column-names-are-bijective-base-26
  ;; There is no zero digit, so the usual division has to borrow one. Getting
  ;; it wrong gives a workbook whose 27th column is BA, which Excel opens
  ;; without complaint and reads wrong.
  (is (= "A" (xlsx/column-name 1)))
  (is (= "Z" (xlsx/column-name 26)))
  (is (= "AA" (xlsx/column-name 27)))
  (is (= "AB" (xlsx/column-name 28)))
  (is (= "AZ" (xlsx/column-name 52)))
  (is (= "BA" (xlsx/column-name 53)))
  (is (= "ZZ" (xlsx/column-name 702)))
  (is (= "AAA" (xlsx/column-name 703)))
  (is (= "B2" (xlsx/cell-ref 2 2))))

(deftest a-package-has-every-part-a-reader-will-look-for
  (let [files (xlsx/xlsx-files (plan))]
    (is (= #{"[Content_Types].xml" "_rels/.rels" "xl/workbook.xml"
             "xl/_rels/workbook.xml.rels" "xl/worksheets/sheet1.xml"}
           (set (keys files))))
    ;; The relationship the workbook is reached by, and the one the sheet is.
    (is (str/includes? (get files "_rels/.rels") "xl/workbook.xml"))
    (is (str/includes? (get files "xl/_rels/workbook.xml.rels")
                       "worksheets/sheet1.xml"))
    (is (str/includes? (get files "xl/workbook.xml") "name=\"Plan\""))))

(deftest every-cell-is-written-as-text
  (let [sheet (get (xlsx/xlsx-files (plan)) "xl/worksheets/sheet1.xml")]
    ;; Including one that looks like a number. Deciding on the workbook's
    ;; behalf that "1200" is twelve hundred, at the moment it leaves for
    ;; Excel, would be the guess arriving late rather than not at all.
    (is (str/includes? sheet "<c r=\"B2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">1200</t></is></c>"))
    (is (str/includes? sheet "<c r=\"A1\" t=\"inlineStr\">"))
    (is (not (str/includes? sheet "sharedStrings")))))

(deftest a-formula-writes-no-cached-value
  (let [sheet (get (xlsx/xlsx-files (plan)) "xl/worksheets/sheet1.xml")]
    ;; There is no evaluator here, so a cached <v> is not something this
    ;; could write. Excel recalculates on open.
    (is (str/includes? sheet "<c r=\"B3\"><f>SUM(B2:B2)</f></c>"))
    (is (not (str/includes? sheet "<f>SUM(B2:B2)</f><v>")))))

(deftest xml-is-escaped
  (let [tab (-> (m/tab "t") (m/put-cell 1 1 "a & b < c > \"d\""))
        sheet (xlsx/sheet-xml tab)]
    (is (str/includes? sheet "a &amp; b &lt; c &gt; &quot;d&quot;"))))

(deftest a-sparse-tab-stays-sparse
  (let [tab (-> (m/tab "t") (m/put-cell 1 1 "a") (m/put-cell 5 3 "b"))
        sheet (xlsx/sheet-xml tab)]
    ;; Rows 2-4 have nothing to say, so they are absent rather than emitted
    ;; as rows of empty cells.
    (is (str/includes? sheet "<row r=\"1\">"))
    (is (str/includes? sheet "<row r=\"5\">"))
    (is (not (str/includes? sheet "<row r=\"2\">")))
    (is (str/includes? sheet "<c r=\"C5\""))))

(deftest an-empty-workbook-still-opens
  (let [files (xlsx/xlsx-files (m/workbook "wb"))]
    ;; A workbook with no tabs is a file Excel refuses, so one is supplied.
    (is (contains? files "xl/worksheets/sheet1.xml"))
    (is (str/includes? (get files "xl/workbook.xml") "sheetId=\"1\""))))

(deftest tabs-come-out-in-a-stable-order
  (let [wb (-> (m/workbook "wb")
               (m/add-tab (m/tab "zebra" {:sheets/title "Zebra"}))
               (m/add-tab (m/tab "alpha" {:sheets/title "Alpha"}))
               (m/add-tab (m/tab "mid" {:sheets/title "Mid"})))
        names (re-seq #"name=\"([^\"]+)\"" (get (xlsx/xlsx-files wb) "xl/workbook.xml"))]
    ;; `:sheets/tabs` is a map, so without an order the sheet Excel opens on
    ;; would depend on hash order and change between runs.
    (is (= ["Alpha" "Mid" "Zebra"] (mapv second names)))
    (is (= 3 (count (filter #(str/starts-with? % "xl/worksheets/")
                            (keys (xlsx/xlsx-files wb))))))))

#?(:clj
   (deftest the-bytes-are-a-zip-a-reader-can-walk
     (let [bytes (xlsx/xlsx-bytes (plan))]
       (is (= [0x50 0x4b] (mapv #(bit-and (int %) 0xff) (take 2 bytes)))
           "PK, so it is a zip")
       (with-open [zip (java.util.zip.ZipInputStream.
                        (java.io.ByteArrayInputStream. bytes))]
         (let [entries (loop [acc []]
                         (if-let [e (.getNextEntry zip)]
                           (recur (conj acc (.getName e)))
                           acc))]
           (is (contains? (set entries) "[Content_Types].xml"))
           (is (contains? (set entries) "xl/worksheets/sheet1.xml"))
           ;; `[Content_Types].xml` must be findable; OPC readers look for it
           ;; by name, and `part-sort-key` keeps the order deterministic.
           (is (= (sort entries) (sort (keys (xlsx/xlsx-files (plan)))))))))))

#?(:clj
   (deftest sheet-ten-is-ordered-after-sheet-two
     ;; Lexical order would put sheet10 between sheet1 and sheet2. A workbook
     ;; with ten tabs is where that stops being theoretical.
     (let [wb (reduce (fn [wb n] (m/add-tab wb (m/tab (format "t%02d" n))))
                      (m/workbook "wb") (range 1 12))
           files (xlsx/xlsx-files wb)
           sheets (filter #(str/starts-with? % "xl/worksheets/") (keys files))]
       (is (= 11 (count sheets)))
       (is (= ["xl/worksheets/sheet1.xml" "xl/worksheets/sheet2.xml"]
              (take 2 (sort-by ooxml/part-sort-key sheets)))))))
