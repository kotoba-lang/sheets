(ns sheets.xlsx
  "A workbook as a .xlsx.

  ## Where this lives, and why not in a library of its own

  `slides` writes .pptx in `slides.pptx` on top of `ooxml`, which supplies
  the OPC vocabulary — content types, relationships, the package — and
  nothing about presentations. The same division works here, and `ooxml`
  already anticipated it: `package-kind` returns `:xlsx` for an `xl/` prefix
  and `part-sort-key` knows how to order `xl/worksheets/sheetN.xml`. A
  `spreadsheetml` repository to hold what turned out to be one namespace
  would have been a repository for the symmetry of it.

  ## Every cell is written as text

  `<c t=\"inlineStr\">`, including one whose value looks like a number.
  `sheets.csv` refuses to guess on the way in for a stated reason — guessing
  is how a part number becomes a float — and writing is the same claim in
  the other direction. A workbook here holds strings because that is what it
  was given; deciding on its behalf that `0042` is forty-two, at the moment
  it leaves for Excel, would be the guess arriving late rather than not at
  all.

  Inline strings also mean no `sharedStrings.xml`: one part fewer, no string
  table to keep in step with the cells that index it, and a file Excel opens
  either way. A workbook large enough for the table to pay for itself is
  larger than anything this Drive produces.

  A formula cell writes `<f>` and no `<v>`. There is no evaluator in this
  library, so a cached value is not something it could write — Excel
  recalculates on open, which is the correct outcome and not a workaround."
  (:require [clojure.string :as str]
            [ooxml.core :as ooxml]
            [sheets.csv :as csv]
            [sheets.model :as model])
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.util.zip ZipEntry ZipOutputStream])))

(def ^:private main-ns
  "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
(def ^:private rels-ns
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships")

(defn column-name
  "1 → A, 26 → Z, 27 → AA.

  Bijective base-26: there is no zero digit, so the usual division has to
  borrow one before each step. Getting this wrong gives a workbook whose
  27th column is `BA`, which Excel opens without complaint and reads wrong."
  [col]
  (loop [n (long col) out ""]
    (if (pos? n)
      (let [rem (mod (dec n) 26)]
        (recur (quot (dec n) 26)
               (str (char (+ (int \A) rem)) out)))
      out)))

(defn cell-ref [row col]
  (str (column-name col) row))

(defn- cell-xml [row col cell]
  (let [ref (cell-ref row col)]
    (cond
      (contains? cell :sheets/formula)
      (str "<c r=\"" ref "\"><f>" (ooxml/xml-esc (:sheets/formula cell)) "</f></c>")

      (some? (:sheets/value cell))
      (str "<c r=\"" ref "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
           (ooxml/xml-esc (:sheets/value cell))
           "</t></is></c>")

      :else nil)))

(defn sheet-xml
  "One worksheet part.

  Rows and cells are emitted in order and only where there is something to
  say: a sparse tab stays sparse, because a row of empty `<c>` elements is
  bytes that mean the same as their absence."
  [tab]
  (let [[rows cols] (csv/tab-bounds tab)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
         "<worksheet xmlns=\"" main-ns "\"><sheetData>"
         (apply str
                (for [row (range 1 (inc rows))
                      :let [cells (str/join (keep #(cell-xml row % (model/get-cell tab row %))
                                                  (range 1 (inc cols))))]
                      :when (seq cells)]
                  (str "<row r=\"" row "\">" cells "</row>")))
         "</sheetData></worksheet>")))

(defn- ordered-tabs
  "The workbook's tabs in a stable order.

  `:sheets/tabs` is a map, so without this the sheet order — and therefore
  which one Excel opens on — would depend on hash order and change between
  runs for no reason a reader could see."
  [workbook]
  (mapv #(get (:sheets/tabs workbook) %) (sort (keys (:sheets/tabs workbook)))))

(defn- workbook-xml [tabs]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<workbook xmlns=\"" main-ns "\" xmlns:r=\"" rels-ns "\"><sheets>"
       (apply str
              (map-indexed
               (fn [index tab]
                 (str "<sheet name=\""
                      (ooxml/xml-esc (or (:sheets/title tab) (:sheets/id tab)
                                         (str "Sheet" (inc index))))
                      "\" sheetId=\"" (inc index)
                      "\" r:id=\"rId" (inc index) "\"/>"))
               tabs))
       "</sheets></workbook>"))

(defn xlsx-files
  "Every part of the .xlsx, as path → text.

  Separate from the zipping so the package can be inspected and asserted
  without a host that has one."
  [workbook]
  (let [tabs (ordered-tabs workbook)
        tabs (if (seq tabs) tabs [(model/tab "Sheet1")])
        sheet-path (fn [n] (str "xl/worksheets/sheet" n ".xml"))]
    (into
     {"[Content_Types].xml"
      (ooxml/content-types-xml
       (concat [(ooxml/default-content-type "rels" (str rels-ns "+xml"))
                (ooxml/default-content-type "xml" "application/xml")
                (ooxml/override-content-type
                 "/xl/workbook.xml"
                 (str "application/vnd.openxmlformats-officedocument."
                      "spreadsheetml.sheet.main+xml"))]
               (map-indexed (fn [index _]
                              (ooxml/override-content-type
                               (str "/" (sheet-path (inc index)))
                               (str "application/vnd.openxmlformats-officedocument."
                                    "spreadsheetml.worksheet+xml")))
                            tabs)))

      "_rels/.rels"
      (ooxml/relationships-xml
       [(ooxml/relationship {:id "rId1"
                             :type (str rels-ns "/officeDocument")
                             :target "xl/workbook.xml"})])

      "xl/workbook.xml" (workbook-xml tabs)

      "xl/_rels/workbook.xml.rels"
      (ooxml/relationships-xml
       (map-indexed (fn [index _]
                      (ooxml/relationship
                       {:id (str "rId" (inc index))
                        :type (str rels-ns "/worksheet")
                        :target (str "worksheets/sheet" (inc index) ".xml")}))
                    tabs))}
     (map-indexed (fn [index tab] [(sheet-path (inc index)) (sheet-xml tab)]) tabs))))

(defn package
  "The parts as an `ooxml/package`, for a caller that wants to look before
  it writes."
  [workbook]
  (ooxml/package (xlsx-files workbook)))

#?(:clj
   (defn xlsx-bytes
     "A JVM byte array containing a .xlsx of `workbook`.

     Parts are written in `ooxml/part-sort-key` order, which puts
     `sheet2.xml` after `sheet10.xml` would sort it lexically — a workbook
     with ten tabs is where that stops being theoretical."
     ^bytes [workbook]
     (let [out (ByteArrayOutputStream.)
           files (xlsx-files workbook)]
       (with-open [zip (ZipOutputStream. out)]
         (doseq [path (sort-by ooxml/part-sort-key (keys files))]
           (.putNextEntry zip (ZipEntry. ^String path))
           (.write zip (.getBytes ^String (get files path) "UTF-8"))
           (.closeEntry zip)))
       (.toByteArray out))))
