;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.externs
  (:require [cljs.util :as util]
            [cljs.js-deps :as js-deps]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [com.google.javascript.jscomp
            CompilerOptions SourceFile JsAst CommandLineRunner]
           [com.google.javascript.jscomp.parsing Config$JsDocParsing]
           [com.google.javascript.rhino
            Node Token JSTypeExpression]
           [java.util.logging Level]))

(def ^:dynamic *ignore-var* false)
(def ^:dynamic *source-file* nil)

;; ------------------------------------------------------------------------------
;; Externs Parsing

(defn annotate [props ty]
  (when (seq props)
    (conj
      (into [] (butlast props))
      (with-meta (last props) ty))))

(defn get-tag [^JSTypeExpression texpr]
  (when-let [root (.getRoot texpr)]
    (if (.isString root)
      (symbol (.getString root))
      (if-let [child (.. root getFirstChild)]
        (if (.isString child)
          (symbol (.. child getString)))))))

(defn get-var-info [^Node node]
  (when node
    (let [info (.getJSDocInfo node)]
      (when info
        (merge
          (if-let [^JSTypeExpression ty (.getType info)]
            {:tag (get-tag ty)}
            (if (or (.isConstructor info) (.isInterface info))
              (let [qname (symbol (.. node getFirstChild getQualifiedName))]
                (cond-> {:tag 'Function}
                  (.isConstructor info) (merge {:ctor qname})
                  (.isInterface info) (merge {:iface qname})))
              (if (.hasReturnType info)
                {:tag 'Function
                 :ret-tag (get-tag (.getReturnType info))
                 :arglists (list (into [] (map symbol (.getParameterNames info))))})))
          {:file *source-file*
           :line (.getLineno node)}
          (when-let [doc (.getOriginalCommentString info)]
            {:doc doc}))))))

(defmulti parse-extern-node
  (fn [^Node node]
    (.getToken node)))

(defmethod parse-extern-node Token/VAR [node]
  (when (> (.getChildCount node) 0)
    (let [ty (get-var-info node)]
      (cond-> (parse-extern-node (.getFirstChild node))
        ty (-> first (annotate ty) vector)))))

(defmethod parse-extern-node Token/EXPR_RESULT [node]
  (when (> (.getChildCount node) 0)
    (parse-extern-node (.getFirstChild node))))

(defmethod parse-extern-node Token/ASSIGN [node]
  (when (> (.getChildCount node) 0)
    (let [ty  (get-var-info node)
          lhs (cond-> (first (parse-extern-node (.getFirstChild node)))
                ty (annotate ty))]
      (if (> (.getChildCount node) 1)
        (let [externs
              (binding [*ignore-var* true]
                (parse-extern-node (.getChildAtIndex node 1)))]
          (conj (map (fn [ext] (concat lhs ext)) externs)
            lhs))
        [lhs]))))

(defmethod parse-extern-node Token/NAME [node]
  (let [lhs (map symbol (string/split (.getQualifiedName node) #"\."))]
    (if (> (.getChildCount node) 0)
      (let [externs (parse-extern-node (.getFirstChild node))]
        (conj (map (fn [ext] (concat lhs ext)) externs)
          lhs))
      [lhs])))

(defmethod parse-extern-node Token/GETPROP [node]
  (when-not *ignore-var*
    (let [props (map symbol (string/split (.getQualifiedName node) #"\."))]
      [(if-let [ty (get-var-info node)]
         (annotate props ty)
         props)])))

(defmethod parse-extern-node Token/OBJECTLIT [node]
  (when (> (.getChildCount node) 0)
    (loop [nodes (.children node)
           externs []]
      (if (empty? nodes)
        externs
        (recur (rest nodes)
          (concat externs (parse-extern-node (first nodes))))))))

(defmethod parse-extern-node Token/STRING_KEY [node]
  (let [lhs (map symbol (string/split (.getString node) #"\."))]
    (if (> (.getChildCount node) 0)
      (let [externs (parse-extern-node (.getFirstChild node))]
        (conj (map (fn [ext] (concat lhs ext)) externs)
          lhs))
      [lhs])))

(defmethod parse-extern-node :default [node])

(defn parse-externs [^SourceFile source-file]
  (binding [*source-file* (.getName source-file)]
    (let [^CompilerOptions compiler-options
          (doto (CompilerOptions.)
            (.setParseJsDocDocumentation
              Config$JsDocParsing/INCLUDE_DESCRIPTIONS_WITH_WHITESPACE))
          closure-compiler
          (doto
            (let [compiler (com.google.javascript.jscomp.Compiler.)]
              (com.google.javascript.jscomp.Compiler/setLoggingLevel Level/WARNING)
              compiler)
            (.init (list source-file) '() compiler-options))
          js-ast (JsAst. source-file)
          ^Node root (.getAstRoot js-ast closure-compiler)]
      (loop [nodes (.children root)
             externs []]
        (if (empty? nodes)
          externs
          (let [node (first nodes)
                new-extern (parse-extern-node node)]
            (recur (rest nodes) (concat externs new-extern))))))))

(defn index-externs [externs]
  (reduce
    (fn [m xs]
      (cond-> m
        (seq xs) (update-in xs merge {})))
    {} externs))

(defn externs-map
  ([]
   (externs-map (CommandLineRunner/getDefaultExterns)))
  ([sources]
   (externs-map sources
     '{eval {}
       global {}
       goog {nodeGlobalRequire {}}
       COMPILED {}
       TypeError {}
       Error {prototype {number {} columnNumber {}}}
       ReferenceError {}}))
  ([sources defaults]
   (let [sources (if-not (empty? sources)
                   sources
                   (CommandLineRunner/getDefaultExterns))]
     (reduce
       (fn [externs externs-file]
         (util/map-merge
           externs (index-externs (parse-externs externs-file))))
       defaults sources))))

(defn analyze-goog-file [f]
  (let [rsrc (io/resource f)
        desc (js-deps/parse-js-ns (line-seq (io/reader rsrc)))]
    ;; TODO: figure out what to do about other provides
    [(first (:provides desc))
     ]))

(comment

  (analyze-goog-file "goog/string/string.js")

  (require '[clojure.java.io :as io]
           '[cljs.closure :as closure]
           '[clojure.pprint :refer [pprint]]
           '[cljs.js-deps :as js-deps])

  (get (js-deps/js-dependency-index {}) "goog.string")

  ;; {:tag Function :ret-tag boolean}
  (->
    (nth
      (parse-externs
        (closure/js-source-file "goog/string/string.js"
          (io/input-stream (io/resource "goog/string/string.js"))))
      2)
    last meta)

  (parse-externs
    (closure/js-source-file "goog/string/string.js"
      (io/input-stream (io/resource "goog/string/string.js"))))

  (externs-map
    [(closure/js-source-file "goog/string/string.js"
       (io/input-stream (io/resource "goog/string/string.js")))]
    {})

  (externs-map)

  (-> (externs-map)
    (find 'console) first meta)

  (get (externs-map) 'Function)

  (get (externs-map) 'Error)

  ;; values are not on the prototype
  (get (externs-map) 'Symbol)
  (get (externs-map) 'Number)

  (-> (get-in (externs-map) '[Window prototype])
    (find 'performance) first meta)

  ;; webkit_dom.js defines Console and Window.prototype.console
  (filter
    (fn [s]
      (let [m (-> s parse-externs index-externs)]
        (get-in m '[Window prototype console])))
    (CommandLineRunner/getDefaultExterns))

  (->
    (filter
      (fn [s]
        (= "externs.zip//webkit_dom.js" (.getName s)))
      (CommandLineRunner/getDefaultExterns))
    first parse-externs index-externs
    (find 'console) first meta)

  (->
    (filter
      (fn [s]
        (= "externs.zip//webkit_dom.js" (.getName s)))
      (CommandLineRunner/getDefaultExterns))
    first parse-externs index-externs
    (get-in '[Console prototype])
    (find 'log) first meta)

  (require '[clojure.java.io :as io]
           '[cljs.closure :as cc])

  (-> (cc/js-source-file nil (io/file "react.ext.js"))
    parse-externs index-externs
    (get 'React)
    (find 'Component) first meta)
  )