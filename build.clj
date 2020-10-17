#!/usr/bin/env bb

(ns build
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [cheshire.core :as cheshire]
            [clojure.string :as str])
  (:import (java.io File)))

(defn package-json
  [[package-name _]]
  {"name"            (name package-name)
   "description"     ""
   "version"         "0.1.0"
   "dependencies"    {}
   "devDependencies" '{shadow-cljs "2.11.4"}})

(defn slurp-edn [f]
  (edn/read-string (slurp f)))

(defn webjar-config->build-config
  [{:keys [package entries exclude macros compiler-options]}]
  {:dependencies [package]
   :builds       {:webjar (cond-> {:target     :bootstrap
                                   :output-dir "out"
                                   :entries    (into '[cljs.js] entries)
                                   :exclude    (into '[cljs.js] exclude)
                                   :modules    (vec macros)}
                            compiler-options (assoc :compiler-options compiler-options))}})

(defn out-dir [[package-name package-version]]
  (let [path ["target" (namespace package-name) (name package-name) (str package-version)]]
    (apply io/file (filter identity path))))

(defn spit-shadow-cljs-edn!
  [webjar-config build-config]
  (let [out-file (io/file (out-dir (:package webjar-config)) "shadow-cljs.edn")]
    (io/make-parents out-file)
    (spit out-file (pr-str build-config))))

(defn spit-package-json! [webjar-config]
  (let [out-file (io/file (out-dir (:package webjar-config)) "package.json")]
    (io/make-parents out-file)
    (spit out-file (cheshire/generate-string (package-json (:package webjar-config))))))

(defn build! [ctx f]
  (let [webjar-config (slurp-edn f)
        build-config  (webjar-config->build-config webjar-config)
        out-dir       (out-dir (:package webjar-config))
        dir           (str out-dir)]
    (println dir)
    (spit-shadow-cljs-edn! webjar-config build-config)
    (spit-package-json! webjar-config)
    (println (sh/sh "npm" "install" :dir dir))
    (println (sh/sh "shadow-cljs" "compile" "webjar" :dir dir))
    (println (sh/sh "aws" "s3" "cp" "--recursive"
                    (str (io/file out-dir "out"))
                    (format "s3://%s/%s/%s/%s"
                            (:bucket-name ctx)
                            (:version ctx)
                            (-> webjar-config :package first)
                            (-> webjar-config :package second))))
    nil))

(defn webjar-files []
  (->> (io/file "repository")
       (file-seq)
       (filter (fn [^File file]
                 (str/ends-with? (.getName file) ".edn")))))

(defn build-webjars! [ctx]
  (doseq [webjar-file (webjar-files)]
    (build! ctx webjar-file)))

(comment
 (build-webjars!
  {:bucket-name "webjars.cljspad.dev"
   :version     "1"}))

(let [ctx (slurp-edn "webjars.edn")]
  (try (build-webjars! ctx)
       (System/exit 0)
       (catch Throwable e
         (.printStackTrace e)
         (System/exit 1))))