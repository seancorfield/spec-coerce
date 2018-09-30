(ns spec-coerce.core
  (:refer-clojure :exclude [def])
  (:require [com.wsscode.spec-inspec :as si]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [clojure.string :as str]
    #?(:clj
            [clojure.instant]))
  #?(:clj
     (:import (java.util Date TimeZone UUID)
              (java.net URI)
              (java.time LocalDate LocalDateTime ZoneId)
              (java.time.format DateTimeFormatter))))

(declare coerce)

(defonce ^:private registry-ref (atom {}))

(defn parse-long [x]
  (if (string? x)
    (try
      #?(:clj  (Long/parseLong x)
         :cljs (if (= "NaN" x)
                 js/NaN
                 (let [v (js/parseInt x)]
                  (if (js/isNaN v) x v))))
      (catch #?(:clj Exception :cljs :default) _
        x))
    x))

(defn parse-double [x]
  (if (string? x)
    (try
      #?(:clj  (case x
                 "##-Inf" ##-Inf
                 "##Inf" ##Inf
                 "##NaN" ##NaN
                 "NaN" ##NaN
                 "Infinity" ##Inf
                 "-Infinity" ##-Inf
                 (Double/parseDouble x))
         :cljs (if (= "NaN" x)
                 js/NaN
                 (let [v (js/parseFloat x)]
                  (if (js/isNaN v) x v))))
      (catch #?(:clj Exception :cljs :default) _
        x))
    x))

(defn parse-uuid [x]
  (if (string? x)
    (try
      #?(:clj  (UUID/fromString x)
         :cljs (uuid x))
      (catch #?(:clj Exception :cljs :default) _
        x))
    x))

#?(:clj (def ^:dynamic *inst-formats*
          ["yyyy/M/d H:m:s" "yyyy/M/d H:m" "yyyy/M/d"
           "M/d/yyyy H:m:s" "M/d/yyyy H:m" "M/d/yyyy"
           "yyyy-M-d H:m:s" "yyyy-M-d H:m" "yyyy-M-d"
           "M-d-yyyy H:m:s" "M-d-yyyy H:m" "M-d-yyyy"
           "EEE MMM dd HH:mm:ss zzz yyyy"]))

#?(:clj
   (defn- flexible-parse-inst [x]
     (try
       (clojure.instant/read-instant-timestamp x)
       (catch Exception _
         (let [zone (ZoneId/of (.getID (TimeZone/getDefault)))]
           (or (some #(try
                        (Date/from
                         (.toInstant
                          (.atZone
                           (LocalDateTime/parse x (DateTimeFormatter/ofPattern %))
                           zone)))
                        (catch Exception _)) *inst-formats*)
               (some #(try
                        (Date/from
                         (.toInstant
                          (.atStartOfDay
                           (LocalDate/parse x (DateTimeFormatter/ofPattern %))
                           zone)))
                        (catch Exception _)) *inst-formats*)
               x))))))

(defn parse-inst [x]
  (if (string? x)
    (try
      #?(:clj  (flexible-parse-inst x)
         :cljs (js/Date. x))
      (catch #?(:clj Exception :cljs :default) _
        x))
    x))

(defn parse-boolean [x]
  (case x
    "true" true
    "false" false
    x))

(defn parse-keyword [x]
  (if (string? x)
    (if (str/starts-with? x ":")
      (keyword (subs x 1))
      (keyword x))
    x))

(defn parse-symbol [x]
  (if (string? x)
    (symbol x)
    x))

(defn parse-ident [x]
  (if (string? x)
    (if (str/starts-with? x ":")
      (parse-keyword x)
      (symbol x))
    x))

(defn parse-nil [x]
  (if (and (string? x)
           (#{"nil" "null"} (str/trim x)))
    nil
    x))

(defn parse-or [[_ & pairs]]
  (fn [x]
    (reduce
      (fn [x [_ pred]]
        (let [coerced (coerce pred x)]
          (if (= x coerced)
            x
            (reduced coerced))))
      x
      (partition 2 pairs))))

(defn parse-coll-of [[_ pred & _]]
  (fn [x]
    (if (sequential? x)
      (into (empty x) (map (partial coerce pred)) x)
      x)))

(defn parse-map-of [[_ kpred vpred & _]]
  (fn [x]
    (if (associative? x)
      (into {} (map (fn [[k v]]
                      [(coerce kpred k)
                       (coerce vpred v)]))
            x)
      x)))

#?(:clj
   (defn parse-decimal [x]
     (if (string? x)
       (if (str/ends-with? x "M")
         (bigdec (subs x 0 (dec (count x))))
         (bigdec x))
       x)))

#?(:clj
   (defn parse-uri [x]
     (if (string? x)
       (URI. x)
       x)))

(defmulti sym->coercer
  (fn [x]
    (if (sequential? x)
      (first x)
      x)))

(defmethod sym->coercer `number? [_] parse-double)
(defmethod sym->coercer `integer? [_] parse-long)
(defmethod sym->coercer `int? [_] parse-long)
(defmethod sym->coercer `pos-int? [_] parse-long)
(defmethod sym->coercer `neg-int? [_] parse-long)
(defmethod sym->coercer `nat-int? [_] parse-long)
(defmethod sym->coercer `even? [_] parse-long)
(defmethod sym->coercer `odd? [_] parse-long)
(defmethod sym->coercer `float? [_] parse-double)
(defmethod sym->coercer `double? [_] parse-double)
(defmethod sym->coercer `boolean? [_] parse-boolean)
(defmethod sym->coercer `ident? [_] parse-ident)
(defmethod sym->coercer `simple-ident? [_] parse-ident)
(defmethod sym->coercer `qualified-ident? [_] parse-ident)
(defmethod sym->coercer `keyword? [_] parse-keyword)
(defmethod sym->coercer `simple-keyword? [_] parse-keyword)
(defmethod sym->coercer `qualified-keyword? [_] parse-keyword)
(defmethod sym->coercer `symbol? [_] parse-symbol)
(defmethod sym->coercer `simple-symbol? [_] parse-symbol)
(defmethod sym->coercer `qualified-symbol? [_] parse-symbol)
(defmethod sym->coercer `uuid? [_] parse-uuid)
(defmethod sym->coercer `inst? [_] parse-inst)
(defmethod sym->coercer `nil? [_] parse-nil)
(defmethod sym->coercer `false? [_] parse-boolean)
(defmethod sym->coercer `true? [_] parse-boolean)
(defmethod sym->coercer `zero? [_] parse-long)
(defmethod sym->coercer `s/or [form] (parse-or form))
(defmethod sym->coercer `s/coll-of [form] (parse-coll-of form))
(defmethod sym->coercer `s/map-of [form] (parse-map-of form))

#?(:clj (defmethod sym->coercer `uri? [_] parse-uri))
#?(:clj (defmethod sym->coercer `decimal? [_] parse-decimal))

(defmethod sym->coercer :default [_] identity)

(defn- keys-parser
  [[_ & {:keys [req-un opt-un]}]]
  (let [keys-mapping (into {} (map #(vector (keyword (name %)) %) (concat req-un opt-un)))]
    (fn [x]
      (with-meta
        (reduce-kv (fn [m k v]
                     (assoc m k (coerce (or (keys-mapping k) k) v)))
                   {}
                   x)
        (meta x)))))

(defmethod sym->coercer `s/keys
  [form]
  (keys-parser form))

(defn pull-nilable [k]
  (if (and (seq? k)
           (= `s/nilable (first k)))
    (second k)
    k))

(defn infer-coercion [k]
  "Infer a coercer function from a given spec."
  (-> (si/spec->root-sym k)
      (pull-nilable)
      (sym->coercer)))

(defn coerce-fn [k]
  "Get the coercing function from a given key. First it tries to lookup the coercion
  on the registry, otherwise try to infer from the specs. In case nothing is found, identity function is returned."
  (or (when (qualified-keyword? k)
        (si/registry-lookup @registry-ref k))
      (infer-coercion k)))

(defn coerce [k x]
  "Coerce a value x using coercer k. This function will first try to use
  a coercer from the registry, otherwise it will try to infer a coercer from
  the spec with the same name. Coercion will only be tried if x is a string.
  Returns original value in case a coercer can't be found."
  (if-let [coerce-fn (coerce-fn k)]
    (coerce-fn x)
    x))

(defn ^:skip-wiki def-impl [k coerce-fn]
  (assert (and (ident? k) (namespace k)) "k must be namespaced keyword")
  (swap! registry-ref assoc k coerce-fn)
  k)

(s/fdef def-impl
  :args (s/cat :k qualified-keyword?
               :coercion ifn?)
  :ret any?)

(defmacro def
  "Given a namespace-qualified keyword, and a coerce function, makes an entry in the
  registry mapping k to the coerce function."
  [k coercion]
  `(def-impl '~k ~coercion))

(s/fdef def
  :args (s/cat :k qualified-keyword?
               :coercion any?)
  :ret qualified-keyword?)

(defn coerce-structure
  "Recursively coerce map values on a structure."
  ([x] (coerce-structure x {}))
  ([x {::keys [overrides]}]
    (walk/prewalk (fn [x]
                    (if (map? x)
                      (with-meta (into {} (map (fn [[k v]]
                                                 (let [coercion (get overrides k k)]
                                                   [k (coerce coercion v)]))) x)
                                 (meta x))
                      x))
                  x)))
