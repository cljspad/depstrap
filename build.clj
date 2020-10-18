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

(defn depstrap-config->build-config
  [{:keys [package entries exclude macros compiler-options]}]
  {:dependencies [package]
   :builds       {:depstrap (cond-> {:target     :bootstrap
                                     :output-dir "out"
                                     :entries    (into '[cljs.js] entries)
                                     :exclude    (into '#{cljs.js} exclude)
                                     :macros     (vec macros)}
                              compiler-options (assoc :compiler-options compiler-options))}})

(defn out-dir [[package-name package-version]]
  (let [path ["target" (namespace package-name) (name package-name) (str package-version)]]
    (apply io/file (filter identity path))))

(defn spit-shadow-cljs-edn!
  [depstrap-config build-config]
  (let [out-file (io/file (out-dir (:package depstrap-config)) "shadow-cljs.edn")]
    (io/make-parents out-file)
    (spit out-file (pr-str build-config))))

(defn spit-package-json! [depstrap-config]
  (let [out-file (io/file (out-dir (:package depstrap-config)) "package.json")]
    (io/make-parents out-file)
    (spit out-file (cheshire/generate-string (package-json (:package depstrap-config))))))

(defn build! [ctx f]
  (let [depstrap-config (slurp-edn f)
        build-config  (depstrap-config->build-config depstrap-config)
        out-dir       (out-dir (:package depstrap-config))
        dir           (str out-dir)]
    (println dir)
    (spit-shadow-cljs-edn! depstrap-config build-config)
    (spit-package-json! depstrap-config)
    (println (sh/sh "npm" "install" :dir dir))
    (println (sh/sh "shadow-cljs" "compile" "depstrap" :dir dir))
    (println (sh/sh "aws" "s3" "cp" "--recursive"
                    (str (io/file out-dir "out"))
                    (format "s3://%s/%s/%s/%s"
                            (:bucket-name ctx)
                            (:version ctx)
                            (-> depstrap-config :package first)
                            (-> depstrap-config :package second))))
    nil))

(defn depstrap-files []
  (->> (io/file "repository")
       (file-seq)
       (filter (fn [^File file]
                 (str/ends-with? (.getName file) ".edn")))))

(defn build-depstrap! [ctx]
  (doseq [depstrap-file (depstrap-files)]
    (build! ctx depstrap-file)))

(comment
 (build-depstrap!
  {:bucket-name "depstrap.cljspad.dev"
   :version     "1"}))

(let [ctx (slurp-edn "depstrap.edn")]
  (try (build-depstrap! ctx)
       (System/exit 0)
       (catch Throwable e
         (.printStackTrace e)
         (System/exit 1))))