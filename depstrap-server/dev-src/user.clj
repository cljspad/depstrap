(ns user
  (:require [integrant.core :as ig]
            [depstrap.server :as server]
            [clj-http.client :as http]))

(defn dev-opts []
  {:region "us-east-1"
   :bucket "depstrap.cljspad.dev"
   :port   3001})

(defonce sys
  (atom nil))

(defn start! []
  (reset! sys (ig/init (server/config (dev-opts)))))

(defn stop! []
  (swap! sys ig/halt!))

(comment
 (stop!)
 (start!)
 (http/post "http://localhost:3001/api/1/bootstrap"
            {:body    (pr-str [['reagent "1.0.0-alpha2"]])
             :headers {"Content-Type" "application/edn"}})

 (http/get "http://localhost:3001/api/1/bootstrap/f62e20b2-0512-48ed-9794-07552bcea212/index.transit.json"))