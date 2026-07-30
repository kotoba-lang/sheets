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
  recalculates on open, which is the correct outcome and not a workaround.

  ## Reading meets more shapes than writing chose

  A .xlsx from Excel is not the one this namespace produces: its strings are
  in a shared table, its numbers carry no `t` at all, and its formulas come
  with the value Excel last calculated. The reader handles all of them and
  keeps the formula rather than the cached value — see `cell-value`.

  What it does not handle is styles, and therefore dates: Excel stores a
  date as a serial number whose *format* is what makes it a date, so one
  arrives here as `45000` rather than a day. Reading styles is the next
  thing this needs, and pretending otherwise would put a wrong date in a
  cell rather than an obvious number."
  (:require [clojure.string :as str]
            [ooxml.core :as ooxml]
            [sheets.csv :as csv]
            [sheets.model :as model]
            [xml.parse :as xml])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
                   [java.util.zip ZipEntry ZipInputStream ZipOutputStream])))

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

;; ── reading one back ────────────────────────────────────────────────────────
;;
;; Writing got to choose one representation for everything. Reading meets all
;; of them, because a .xlsx from Excel is not the .xlsx this namespace
;; produces: its strings live in a shared table, its numbers have no `t` at
;; all, and its formulas carry the value Excel last calculated.

(defn column-number
  "`A` → 1, `AA` → 27. The inverse of `column-name`, and the same borrowing
  in the other direction."
  [letters]
  (reduce (fn [n ch] (+ (* 26 n) (inc (- (int ch) (int \A)))))
          0
          (str/upper-case (str letters))))

(defn parse-ref
  "`B12` → `[12 2]`. Nil for anything that is not a cell reference, so a
  malformed one is dropped rather than becoming cell `[0 0]`."
  [ref]
  (when-let [[_ letters digits] (re-matches #"([A-Za-z]+)(\d+)" (str ref))]
    [#?(:clj (Long/parseLong digits) :cljs (js/parseInt digits 10))
     (column-number letters)]))

(defn- all-text
  "Every `<t>` under `el`, concatenated.

  A shared string can be a single `<t>` or a run of `<r><t>` fragments that
  differ only in formatting; a reader that took the first would silently
  truncate every styled string to its first run."
  [el]
  (apply str (map xml/el-text (xml/find-all el :t))))

(defn shared-strings
  "The string table, or an empty vector when the package has none.

  `xlsx-files` never writes one — inline strings need no table — so a
  package produced here reads back through the same code path as one from
  Excel, with the table simply empty."
  [files]
  (if-let [xml-str (get files "xl/sharedStrings.xml")]
    (mapv all-text (xml/find-all (xml/parse xml-str) :si))
    []))

(defn- cell-value
  "One `<c>` as a cell map, or nil when it holds nothing.

  Four shapes, and the `t` attribute names three of them:

    t=\"s\"          `<v>` is an index into the shared string table
    t=\"inlineStr\"  `<is><t>` is the text
    t=\"str\"        `<v>` is a formula's cached string result
    absent          `<v>` is a number, written as its literal text

  The number is kept as text on purpose. This model has no number type —
  `sheets.csv` reads every field as a string for a stated reason — and
  turning `1200` into a long here would be that guess arriving from the
  other side of the same document."
  [cell strings]
  (let [t (xml/el-attr cell "t")
        formula (first (xml/find-all cell :f))
        v (first (xml/find-all cell :v))
        text (cond
               (= t "inlineStr") (all-text cell)
               (= t "s") (let [i (some-> v xml/el-text str/trim)]
                           (get strings
                                #?(:clj (try (Long/parseLong i) (catch Exception _ -1))
                                   :cljs (js/parseInt i 10))))
               :else (some-> v xml/el-text))]
    (cond
      ;; A formula wins over its cached value: the formula is what the
      ;; document says and the value is what Excel last thought. Keeping the
      ;; value instead would turn a spreadsheet into a printout of one.
      formula {:sheets/formula (xml/el-text formula)}
      (not (str/blank? (str text))) {:sheets/value (str text)}
      :else nil)))

(defn sheet->tab
  "One worksheet part as a tab."
  ([xml-str id] (sheet->tab xml-str id [] {}))
  ([xml-str id strings attrs]
   (let [root (xml/parse xml-str)]
     (reduce (fn [tab cell]
               (let [[row col] (parse-ref (xml/el-attr cell "r"))
                     value (when row (cell-value cell strings))]
                 (if value
                   (assoc-in tab [:sheets/cells [row col]] value)
                   tab)))
             (model/tab id attrs)
             (xml/find-all root :c)))))

(defn- sheet-order
  "Worksheet part paths in the order the workbook declares, resolved through
  its relationships.

  Not `sheet1, sheet2, …` by name: a workbook may relate rId1 to
  `worksheets/sheet3.xml`, and the order Excel shows is the order in
  `<sheets>`, not the order the files happen to be numbered."
  [files]
  (let [rels (when-let [x (get files "xl/_rels/workbook.xml.rels")]
               (into {} (map (juxt #(xml/el-attr % "Id")
                                   #(xml/el-attr % "Target")))
                     (xml/find-all (xml/parse x) :Relationship)))
        sheets (some-> (get files "xl/workbook.xml") xml/parse (xml/find-all :sheet))]
    (vec
     (keep (fn [sheet]
             (let [target (get rels (or (xml/el-attr sheet "r:id")
                                        (xml/el-attr sheet "id")))
                   path (when target
                          (if (str/starts-with? target "/")
                            (subs target 1)
                            (str "xl/" target)))]
               (when (contains? files path)
                 {:path path :name (xml/el-attr sheet "name")})))
           sheets))))

(defn workbook-from-files
  "A workbook from the parts of a .xlsx.

  Tabs are keyed by their sheet name, which is what a reader sees and what
  `workbook->csv` takes. A workbook whose `<sheets>` cannot be resolved
  falls back to every worksheet part in `part-sort-key` order, so a package
  missing its relationships still comes in rather than coming in empty."
  ([files] (workbook-from-files files "wb"))
  ([files id]
   (let [strings (shared-strings files)
         declared (sheet-order files)
         sheets (if (seq declared)
                  declared
                  (->> (keys files)
                       (filter #(str/starts-with? % "xl/worksheets/"))
                       (sort-by ooxml/part-sort-key)
                       (map-indexed (fn [i path]
                                      {:path path :name (str "Sheet" (inc i))}))))]
     (reduce (fn [wb {:keys [path name]}]
               (model/add-tab wb (sheet->tab (get files path) name strings
                                             {:sheets/title name})))
             (model/workbook id)
             sheets))))

#?(:clj
   (defn xlsx-entries
     "Every part of a .xlsx byte array, as path → text."
     [^bytes bytes]
     (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
       (loop [acc {}]
         (if-let [entry (.getNextEntry zip)]
           (let [out (ByteArrayOutputStream.)
                 buf (byte-array 8192)]
             (loop []
               (let [n (.read zip buf)]
                 (when (pos? n) (.write out buf 0 n) (recur))))
             (recur (assoc acc (.getName entry) (String. (.toByteArray out) "UTF-8"))))
           acc)))))

#?(:clj
   (defn workbook-from-bytes
     "A workbook from .xlsx bytes."
     ([^bytes bytes] (workbook-from-bytes bytes "wb"))
     ([^bytes bytes id] (workbook-from-files (xlsx-entries bytes) id))))
