(ns webjars.api
  (:require [cljs.tools.reader.edn :as edn]
            [shadow.cljs.bootstrap.browser :as boot]
            [cljs.spec.alpha :as s]))

(goog-define webjars-repository "https://webjars.cljspad.dev")

(s/def :webjars/dependency
  (s/tuple symbol? string?))

(s/def :webjars/dependencies
  (s/coll-of :webjars/dependency))

(def load boot/load)

(defn init
  [compiler-state opts cb]
  (let [repo (or (:webjars/repository opts) webjars-repository)
        deps (s/assert* :webjars/dependencies (:webjars/dependencies opts))
        url  (str repo "/api/1/bootstrap")
        req  (clj->js {:method  "POST"
                       :headers {"Content-Type" "application/edn"}
                       :body    (pr-str deps)})]
    (-> (js/fetch url req)
        (.then (fn [resp]
                 (if (aget resp "ok")
                   (.text resp)
                   (js/console.error (str "Failed to fetch webjar dependencies, server responded with " (aget resp "status"))))))
        (.then (fn [text]
                 (let [{:keys [bootstrap]} (edn/read-string text)]
                   (boot/init compiler-state (assoc opts :path bootstrap) cb))))
        (.catch (fn [e]
                  (js/console.error e "Failed to fetch webjar dependencies"))))))