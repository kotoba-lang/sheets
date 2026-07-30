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

(deftest column-letters-are-letters-on-every-host
  ;; Both directions used to compute a character from `(int \A)`, which is a
  ;; code point on the JVM and 0 in ClojureScript. Under cljs that wrote
  ;; control characters into every cell reference and read every column as 1.
  ;; The JVM cannot see either — these assertions passed throughout — so what
  ;; keeps them honest is `scripts/test-cljs.cljs` running them on the other
  ;; host, not the assertions themselves.
  (is (every? #(re-matches #"[A-Z]+" (xlsx/column-name %)) [1 2 26 27 53 702 703 16384]))
  (is (= 2 (xlsx/column-number "B")) "not 1, which is what a zero code point gives")
  (is (= 16384 (xlsx/column-number "XFD")) "the last column Excel has")
  (is (nil? (xlsx/column-number "A1")) "not a column reference")
  (is (nil? (xlsx/column-number "")))
  (is (= [12 2] (xlsx/parse-ref "B12"))))

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

;; ── reading one back ────────────────────────────────────────────────────────

(deftest column-numbers-invert-column-names
  (doseq [n [1 26 27 28 52 53 702 703 16384]]
    (is (= n (xlsx/column-number (xlsx/column-name n))) (str "column " n)))
  (is (= [12 2] (xlsx/parse-ref "B12")))
  (is (= [1 27] (xlsx/parse-ref "AA1")))
  ;; Nil rather than [0 0], so a malformed reference is dropped instead of
  ;; landing somewhere.
  (is (nil? (xlsx/parse-ref "12B")))
  (is (nil? (xlsx/parse-ref ""))))

(deftest what-this-writes-it-can-read
  (let [back (xlsx/workbook-from-files (xlsx/xlsx-files (plan)) "wb")
        tab (m/tab-by-id back "Plan")]
    (is (= ["Plan"] (keys (:sheets/tabs back))))
    (is (= {:sheets/value "Quarter"} (m/get-cell tab 1 1)))
    (is (= {:sheets/value "1200"} (m/get-cell tab 2 2)))
    (is (= {:sheets/formula "SUM(B2:B2)"} (m/get-cell tab 3 2)))))

(def ^:private excel-style
  "The shapes Excel actually writes, none of which this namespace produces:
  a shared string table, a bare number with no `t`, and a formula carrying
  the value Excel last calculated."
  {"xl/workbook.xml"
   (str "<workbook xmlns:r=\"x\"><sheets>"
        "<sheet name=\"予算\" sheetId=\"1\" r:id=\"rId1\"/>"
        "</sheets></workbook>")
   "xl/_rels/workbook.xml.rels"
   "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
   "xl/sharedStrings.xml"
   (str "<sst><si><t>四半期</t></si>"
        ;; A run-split string: a reader that took the first <t> would
        ;; silently truncate it to "売".
        "<si><r><t>売</t></r><r><t>上</t></r></si></sst>")
   "xl/worksheets/sheet1.xml"
   (str "<worksheet><sheetData><row r=\"1\">"
        "<c r=\"A1\" t=\"s\"><v>0</v></c>"
        "<c r=\"B1\" t=\"s\"><v>1</v></c></row>"
        "<row r=\"2\"><c r=\"A2\"><v>1200</v></c>"
        "<c r=\"B2\"><f>A2*2</f><v>2400</v></c>"
        "<c r=\"C2\" t=\"str\"><f>UPPER(\"x\")</f><v>X</v></c></row>"
        "</sheetData></worksheet>")})

(deftest a-workbook-from-excel-reads
  (let [wb (xlsx/workbook-from-files excel-style "wb")
        tab (m/tab-by-id wb "予算")]
    (is (= ["予算"] (keys (:sheets/tabs wb))) "the tab is keyed by the name a reader sees")
    (is (= {:sheets/value "四半期"} (m/get-cell tab 1 1)) "a shared string")
    (is (= {:sheets/value "売上"} (m/get-cell tab 1 2)) "and one split across runs")
    ;; Kept as text. This model has no number type, and turning 1200 into a
    ;; long here is the guess arriving from the other side of the document.
    (is (= {:sheets/value "1200"} (m/get-cell tab 2 1)))
    ;; The formula wins over its cached value: the formula is what the
    ;; document says, the value is what Excel last thought.
    (is (= {:sheets/formula "A2*2"} (m/get-cell tab 2 2)))
    (is (= {:sheets/formula "UPPER(\"x\")"} (m/get-cell tab 2 3)))))

(deftest sheets-come-in-the-order-the-workbook-declares
  ;; Not by file number: a workbook may relate rId1 to sheet3.xml, and what
  ;; Excel shows is the order in <sheets>.
  (let [files {"xl/workbook.xml"
               (str "<workbook xmlns:r=\"x\"><sheets>"
                    "<sheet name=\"Second\" r:id=\"rId1\"/>"
                    "<sheet name=\"First\" r:id=\"rId2\"/></sheets></workbook>")
               "xl/_rels/workbook.xml.rels"
               (str "<Relationships>"
                    "<Relationship Id=\"rId1\" Target=\"worksheets/sheet3.xml\"/>"
                    "<Relationship Id=\"rId2\" Target=\"worksheets/sheet1.xml\"/>"
                    "</Relationships>")
               "xl/worksheets/sheet1.xml"
               "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>one</t></is></c></row></sheetData></worksheet>"
               "xl/worksheets/sheet3.xml"
               "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>three</t></is></c></row></sheetData></worksheet>"}
        wb (xlsx/workbook-from-files files "wb")]
    (is (= {:sheets/value "three"} (m/get-cell (m/tab-by-id wb "Second") 1 1)))
    (is (= {:sheets/value "one"} (m/get-cell (m/tab-by-id wb "First") 1 1)))))

(deftest a-package-with-no-relationships-still-comes-in
  ;; Rather than coming in empty, which is the failure that looks like a
  ;; working import of an empty file.
  (let [wb (xlsx/workbook-from-files
            {"xl/worksheets/sheet1.xml"
             "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>hi</t></is></c></row></sheetData></worksheet>"}
            "wb")]
    (is (= {:sheets/value "hi"} (m/get-cell (m/tab-by-id wb "Sheet1") 1 1)))))

#?(:clj
   (deftest the-bytes-round-trip
     (let [back (xlsx/workbook-from-bytes (xlsx/xlsx-bytes (plan)) "wb")]
       (is (= {:sheets/value "Q1"} (m/get-cell (m/tab-by-id back "Plan") 2 1)))
       (is (= {:sheets/formula "SUM(B2:B2)"} (m/get-cell (m/tab-by-id back "Plan") 3 2))))))

;; ── dates ───────────────────────────────────────────────────────────────────

(deftest serials-land-on-the-dates-excel-shows
  ;; Anchors, not arithmetic restated. Each was read off a calendar: if the
  ;; epoch constant is wrong these all move together, which is exactly the
  ;; failure a single anchor would hide.
  (is (= "1900-01-01" (xlsx/serial->text 1 false)))
  (is (= "2023-01-01" (xlsx/serial->text 44927 false)))
  (is (= "2023-03-15" (xlsx/serial->text 45000 false)))
  (is (= "2025-01-01" (xlsx/serial->text 45658 false))))

(deftest the-1900-leap-bug-is-honoured
  ;; Excel believes 1900-02-29 existed. Below serial 60 the sheet and the
  ;; calendar agree; above it everything is one day further along than the
  ;; arithmetic says.
  (is (= "1900-02-28" (xlsx/serial->text 59 false)))
  (is (= "1900-03-01" (xlsx/serial->text 61 false))))

(deftest the-1904-workbook-counts-from-1904
  ;; 1462 days apart — four years and a day. Read as the wrong system this
  ;; produces a plausible date rather than an obvious error, which is why the
  ;; workbook is asked.
  (is (= "1904-01-01" (xlsx/serial->text 0 true)))
  (is (= "2023-03-15" (xlsx/serial->text 43538 true))))

(deftest a-fraction-of-a-day-is-a-time
  (is (= "2023-03-15T12:00:00" (xlsx/serial->text 45000.5 false)))
  (is (= "2023-03-15T09:30:00" (xlsx/serial->text 45000.395833 false)))
  ;; Midnight has no time to show, so it is a plain date.
  (is (= "2023-03-15" (xlsx/serial->text 45000.0 false))))

(deftest format-codes-are-read-not-scanned
  (is (xlsx/date-format-code? "yyyy-mm-dd"))
  (is (xlsx/date-format-code? "h:mm:ss"))
  (is (xlsx/date-format-code? "[$-409]d\\-mmm;@"))
  ;; A quoted literal is a word, not four format tokens.
  (is (not (xlsx/date-format-code? "\"month\" 0")))
  ;; A backslash escapes what follows it.
  (is (not (xlsx/date-format-code? "\\d 0")))
  ;; A colour condition is not a format.
  (is (not (xlsx/date-format-code? "[Red]0.00")))
  (is (not (xlsx/date-format-code? "#,##0")))
  (is (not (xlsx/date-format-code? "General"))))

(def ^:private styled-package
  "A package shaped the way Excel writes one: shared strings, a style table,
  and cells that carry a position in `cellXfs` rather than a format id."
  {"xl/workbook.xml"
   (str "<workbook><workbookPr date1904=\"0\"/>"
        "<sheets><sheet name=\"Log\" r:id=\"rId1\"/></sheets></workbook>")
   "xl/_rels/workbook.xml.rels"
   "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
   "xl/styles.xml"
   (str "<styleSheet><numFmts>"
        "<numFmt numFmtId=\"164\" formatCode=\"yyyy&quot;年&quot;m&quot;月&quot;\"/>"
        "<numFmt numFmtId=\"165\" formatCode=\"0.00&quot;円&quot;\"/>"
        "</numFmts><cellXfs>"
        "<xf numFmtId=\"0\"/><xf numFmtId=\"14\"/><xf numFmtId=\"164\"/>"
        "<xf numFmtId=\"165\"/><xf numFmtId=\"22\"/>"
        "</cellXfs></styleSheet>")
   "xl/sharedStrings.xml" "<sst><si><t>締切</t></si></sst>"
   "xl/worksheets/sheet1.xml"
   (str "<worksheet><sheetData><row r=\"1\">"
        "<c r=\"A1\" t=\"s\"><v>0</v></c>"
        "<c r=\"B1\" s=\"1\"><v>45000</v></c>"
        "<c r=\"C1\" s=\"2\"><v>45000</v></c>"
        "<c r=\"D1\" s=\"3\"><v>45000</v></c>"
        "<c r=\"E1\" s=\"4\"><v>45000.5</v></c>"
        "<c r=\"F1\" s=\"0\"><v>45000</v></c>"
        "<c r=\"G1\" t=\"s\" s=\"1\"><v>0</v></c>"
        "<c r=\"H1\" s=\"1\"><v>hello</v></c>"
        "</row></sheetData></worksheet>")})

(deftest a-styled-number-comes-in-as-the-date-it-is
  (let [tab (m/tab-by-id (xlsx/workbook-from-files styled-package) "Log")]
    (is (= {:sheets/value "2023-03-15"} (m/get-cell tab 1 2)) "builtin numFmtId 14")
    (is (= {:sheets/value "2023-03-15"} (m/get-cell tab 1 3)) "custom code with a date token")
    (is (= {:sheets/value "2023-03-15T12:00:00"} (m/get-cell tab 1 5)) "date and time")))

(deftest a-number-under-any-other-format-stays-a-number
  (let [tab (m/tab-by-id (xlsx/workbook-from-files styled-package) "Log")]
    ;; The currency code's only letters are inside a quoted literal.
    (is (= {:sheets/value "45000"} (m/get-cell tab 1 4)) "0.00\"円\"")
    (is (= {:sheets/value "45000"} (m/get-cell tab 1 6)) "General")))

(deftest a-date-format-does-not-make-text-a-date
  (let [tab (m/tab-by-id (xlsx/workbook-from-files styled-package) "Log")]
    ;; A shared string is a string whatever the style claims.
    (is (= {:sheets/value "締切"} (m/get-cell tab 1 7)))
    ;; And a word under a date format is that word, not 1899-12-30.
    (is (= {:sheets/value "hello"} (m/get-cell tab 1 8)))))

(deftest the-style-index-is-a-position-not-a-format-id
  ;; `s="3"` is the fourth entry of cellXfs, which points at numFmtId 165.
  ;; Read as an id it would be 3 — a builtin number format — and the cell
  ;; would come in as a number by accident rather than on purpose. Read as a
  ;; position it points at the currency format, which is also not a date, so
  ;; this asks the table directly.
  (let [st (xlsx/styles styled-package)]
    (is (= ["0" "14" "164" "165" "22"] (:formats st)))
    (is (xlsx/date-style? st "1"))
    (is (xlsx/date-style? st "2"))
    (is (not (xlsx/date-style? st "3")))
    (is (xlsx/date-style? st "4"))
    ;; A cell with no style, and a style index past the end of the table.
    (is (not (xlsx/date-style? st nil)))
    (is (not (xlsx/date-style? st "99")))))

(deftest a-package-with-no-styles-reads-numbers-as-numbers
  ;; What this namespace writes has no styles.xml at all, so the absence has
  ;; to be ordinary rather than an error.
  (let [wb (xlsx/workbook-from-files (dissoc styled-package "xl/styles.xml"))
        tab (m/tab-by-id wb "Log")]
    (is (= {:sheets/value "45000"} (m/get-cell tab 1 2)))
    (is (= {:sheets/value "締切"} (m/get-cell tab 1 1)))))

(deftest the-workbook-is-asked-which-epoch-it-uses
  (is (not (xlsx/date1904? styled-package)))
  (is (xlsx/date1904?
       (assoc styled-package "xl/workbook.xml"
              "<workbook><workbookPr date1904=\"1\"/></workbook>")))
  ;; A workbook that says nothing counts from 1900, which is the default and
  ;; the overwhelming majority of files.
  (is (not (xlsx/date1904? (assoc styled-package "xl/workbook.xml" "<workbook/>"))))
  ;; And the 1904 flag actually reaches the cells.
  (let [wb (xlsx/workbook-from-files
            (assoc styled-package "xl/workbook.xml"
                   (str "<workbook><workbookPr date1904=\"1\"/>"
                        "<sheets><sheet name=\"Log\" r:id=\"rId1\"/></sheets></workbook>")))]
    (is (= {:sheets/value "2027-03-16"}
           (m/get-cell (m/tab-by-id wb "Log") 1 2)))))
