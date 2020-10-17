(ns webjars.server
  (:require [integrant.core :as ig]
            [cognitect.aws.client.api :as aws]
            [webjars.server.manifest]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [reitit.ring :as ring]
            [clojure.edn :as edn]
            [clj-http.client :as http]
            [cognitect.transit :as t]
            [ring.middleware.cors :refer [wrap-cors]])
  (:import (java.util UUID)
           (org.eclipse.jetty.server Server)
           (java.io ByteArrayOutputStream BufferedInputStream))
  (:gen-class))

(defmethod ig/init-key :server/deps-store
  [_ _]
  (atom {}))

(defmethod ig/init-key :s3/client
  [_ {:keys [region]}]
  (aws/client {:api :s3 :region region}))

(defmethod ig/init-key :ring/server
  [_ {:keys [handler port]}]
  (jetty/run-jetty
   (-> handler
       (defaults/wrap-defaults defaults/api-defaults)
       (wrap-cors :access-control-allow-origin #".*"
                  :access-control-allow-methods [:get :post]))
   {:port  port
    :join? false}))

(defmethod ig/halt-key! :ring/server
  [_ ^Server server]
  (.stop server))

(defn manifest-resp
  [{:keys [manifest]} req]
  (let [version (-> req :path-params :version)]
    {:status  200
     :body    (pr-str (get manifest version))
     :headers {"Content-Type" "application/edn"}}))

(defn create-deps-store
  [{:keys [deps-store manifest endpoint]} req]
  (let [version (-> req :path-params :version)
        body    (edn/read-string (slurp (:body req)))
        id      (str (UUID/randomUUID))]
    ;; TODO: validate body + make req idempotent
    (if (get manifest version)
      (do (swap! deps-store assoc-in [version id] body)
          {:status  200
           :body    (pr-str {:id id :bootstrap (format "%s/api/%s/bootstrap/%s" endpoint version id)})
           :headers {"Content-Type" "application/edn"}})
      {:status 404})))

(def not-found
  (constantly {:status 404 :body "" :headers {}}))

(defn read-transit
  [in]
  (let [reader (t/reader in :json)]
    (t/read reader)))

(defn write-transit [data]
  (let [out (ByteArrayOutputStream.)]
    (let [writer (t/writer out :json)]
      (t/write writer data)
      (.toString out))))

(defn index-files [client bucket version deps]
  (->> deps
       (map (fn [[package package-version :as k]]
              [k (str version "/" package "/" package-version "/index.transit.json")]))
       (map (fn [[k key]]
              [k (aws/invoke client {:op      :GetObject
                                     :request {:Bucket bucket
                                               :Key    key}})]))
       (keep (fn [[k resp]]
               (when-not (:cognitect.anomalies/category resp)
                 [k (:Body resp)])))
       (map (fn [[k v]]
              [k (read-transit v)]))
       (into {})))

(defn update-sources
  [package-name package-version sources]
  (let [path (format "/out/%s/%s" (str package-name) (str package-version))]
    (into [] (map (fn [source]
                    (cond-> source
                      (:js-name source) (update :js-name #(str path %))
                      (:ana-name source) (update :ana-name #(str path %))
                      (:source-name source) (update :source-name #(str path %)))))
          sources)))

(defn unify-index
  [files]
  {:exclude (into #{} (mapcat :exclude) files)
   :sources (->> files
                 (map (fn [[[package-name package-version] manifest]]
                        (update manifest :sources #(update-sources package-name package-version %))))
                 (mapcat :sources)
                 ;; TODO: correctly deduplicate + unify deps -- order is important!
                 (vec))})

(defn massage-index-transit-json
  [{:keys [deps-store client bucket]} req]
  (let [version (-> req :path-params :version)
        id      (-> req :path-params :id)]
    (if-let [deps (get-in @deps-store [version id])]
      (let [manifest (->> (index-files client bucket version deps)
                          (unify-index))]
        {:status  200
         :body    (write-transit manifest)
         :headers {"Content-Type" "application/transit+json"}})

      {:status 404})))

(defn read-s3-file
  [{:keys [client bucket]} req]
  (let [version (-> req :path-params :version)
        id      (-> req :path-params :id)
        key     (subs (:uri req)
                      (count (format "/api/%s/bootstrap/%s/out" version id))
                      (count (:uri req)))
        resp    (aws/invoke client {:op      :GetObject
                                    :request {:Bucket bucket
                                              :Key    (str "/" version key)}})]
    (if-not (:cognitect.anomalies/category resp)
      (let [body ^BufferedInputStream (:Body resp)]
        {:status  200
         :body    (.readAllBytes body)
         :headers (->> {"Content-Type"   (when-let [content-type (:ContentType resp)]
                                           (str content-type))
                        "Content-Length" (when-let [content-length (:ConentLength resp)]
                                           (when (pos? content-length)
                                             (str content-length)))
                        "Last-Modified"  (when-let [last-modified (:LastModified resp)]
                                           (str last-modified))
                        "ETag"           (when-let [etag (:Etag resp)]
                                           (str etag))}
                       (filter (fn [[_ v]] (some? v)))
                       (into {}))})
      {:status 404})))

(defn routes [ctx]
  [["/api/:version/manifest.edn" {:get {:handler (partial manifest-resp ctx)}}]
   ["/api/:version/bootstrap" {:post {:handler (partial create-deps-store ctx)}}]
   ["/api/:version/bootstrap/:id/index.transit.json" {:get {:handler (partial massage-index-transit-json ctx)}}]
   ["/api/:version/bootstrap/:id/out/*" {:get {:handler (partial read-s3-file ctx)}}]])

(defn handler [ctx]
  (ring/ring-handler
   (ring/router (routes ctx))
   (ring/routes (ring/redirect-trailing-slash-handler {:method :strip})
                (ring/create-default-handler {:not-acceptable not-found}))))

(defmethod ig/init-key :ring/handler
  [_ {:keys [ctx]}]
  (handler ctx))

(defn config
  [opts]
  {:s3/client         {:region (:region opts)}
   :server/manifest   {:client (ig/ref :s3/client)
                       :bucket (:bucket opts)}
   :server/deps-store {}
   :ring/handler      {:ctx {:client     (ig/ref :s3/client)
                             :bucket     (:bucket opts)
                             :deps-store (ig/ref :server/deps-store)
                             :manifest   (ig/ref :server/manifest)
                             :endpoint   "http://localhost:3001"}}
   :ring/server       {:handler (ig/ref :ring/handler)
                       :port    (:port opts)}})

(defn dev-opts []
  {:region  "us-east-1"
   :bucket  "webjars.cljspad.dev"
   :port    3001})

(defn prod-opts []
  {:region  (System/getenv "S3_REGION")
   :bucket  (System/getenv "S3_BUCKET")
   :port    (Long/parseLong (System/getenv "PORT"))})

(defonce sys
  (atom nil))

(defn start! []
  (reset! sys (ig/init (config (dev-opts)))))

(defn stop! []
  (swap! sys ig/halt!))

(comment
 (stop!)
 (start!)
 (http/post "http://localhost:3001/api/1/bootstrap"
            {:body    (pr-str [['reagent "1.0.0-alpha2"]])
             :headers {"Content-Type" "application/edn"}})

 (http/get "http://localhost:3001/api/1/bootstrap/f62e20b2-0512-48ed-9794-07552bcea212/index.transit.json"))