(ns depstrap.server.manifest
  (:require [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [integrant.core :as ig]))

(defn normalize-dep
  [[k v]]
  (if (set? v)
    [[(symbol k) v]]
    (map (fn [[package-name versions]]
           [(symbol k package-name) versions])
         v)))

(defn manifest
  ([client bucket]
   (->> (manifest client bucket "")
        (map #(str/split % #"\/"))
        (filter (fn [path]
                  (#{3 4} (count path))))
        (remove (fn [path]
                  (#{"src" "js" "ana"} (last path))))
        (vec)
        (sort-by >)
        (reduce (fn [m path]
                  (if (get-in m path)
                    m
                    (update-in m (butlast path) #(conj (set %) (last path)))))
                {})
        (map (fn [[version deps]]
               [version (into {} (mapcat normalize-dep) deps)]))
        (into {})))

  ([client bucket prefix]
   (let [resp     (->> {:op      :ListObjectsV2
                        :request {:Bucket    bucket
                                  :Prefix    prefix
                                  :Delimiter "/"}}
                       (aws/invoke client))
         index?   (->> resp
                       :Contents
                       (filter (fn [{:keys [Key]}]
                                 (str/ends-with? Key "index.transit.json"))))
         prefixes (->> resp
                       :CommonPrefixes
                       (map :Prefix))]
     (if (seq index?)
       prefixes
       (into prefixes (mapcat (partial manifest client bucket)) prefixes)))))

(defmethod ig/init-key :server/manifest
  [_ {:keys [client bucket]}]
  (manifest client bucket))