(ns depstrap.server
  (:require [integrant.core :as ig]
            [cognitect.aws.client.api :as aws]
            [depstrap.server.manifest]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [reitit.ring :as ring]
            [clojure.edn :as edn]
            [cognitect.transit :as t]
            [ring.middleware.cors :refer [wrap-cors]]
            [clojure.pprint :as pprint]
            [hiccup.core :as hiccup])
  (:import (java.util UUID)
           (org.eclipse.jetty.server Server)
           (java.io ByteArrayOutputStream BufferedInputStream)
           (java.util.concurrent CountDownLatch)
           (org.eclipse.jetty.server.handler.gzip GzipHandler))
  (:gen-class))

(defmethod ig/init-key :server/deps-store
  [_ _]
  (atom {}))

(defmethod ig/init-key :s3/client
  [_ {:keys [region]}]
  (aws/client {:api :s3 :region region}))

(defn jetty-configurator
  [^Server server]
  (let [content-types ["text/javascript"
                       "application/javascript"
                       "application/transit+json"
                       "application/edn"
                       "application/json"]
        gzip-handler  (doto (GzipHandler.)
                        (.setIncludedMimeTypes (into-array String content-types))
                        (.setMinGzipSize 1024)
                        (.setHandler (.getHandler server)))]
    (.setHandler server gzip-handler)))

(defmethod ig/init-key :ring/server
  [_ {:keys [handler port]}]
  (jetty/run-jetty
   (-> handler
       (defaults/wrap-defaults defaults/api-defaults)
       (wrap-cors :access-control-allow-origin #".*"
                  :access-control-allow-methods [:get :post]))
   {:port         port
    :join?        false
    :configurator jetty-configurator}))

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

(defn deduplicate-sources [sources]
  (reduce
   (fn [ctx source]
     (if (contains? (:resource-ids ctx) (:resource-id source))
       ctx
       (-> ctx
           (update :sources conj source)
           (update :resource-ids conj (:resource-id source)))))
   {:sources [] :resource-ids #{}}
   sources))

(defn unify-index
  [files]
  {:exclude (into #{} (mapcat :exclude) files)
   :sources (->> files
                 (map (fn [[[package-name package-version] manifest]]
                        (update manifest :sources #(update-sources package-name package-version %))))
                 (mapcat :sources)
                 (deduplicate-sources)
                 (:sources)
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

(defn get-object [client bucket key]
  (aws/invoke client {:op      :GetObject
                      :request {:Bucket bucket
                                :Key    key}}))

(def get-object-mz
  (memoize get-object))

(defn read-s3-file
  [{:keys [client bucket]} req]
  (let [version (-> req :path-params :version)
        id      (-> req :path-params :id)
        key     (subs (:uri req)
                      (count (format "/api/%s/bootstrap/%s/out" version id))
                      (count (:uri req)))
        resp    (get-object-mz client bucket (str "/" version key))]
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

(defn index-html [manifest]
  [:html {:lang "en"}
   [:head
    [:title "depstrap manifest"]
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width"}]
    [:meta {:content "ClojureScript repository for cljs.js environments" :name "description"}]
    [:meta {:content "clojure,clojurescript,sandbox,bootstrap,compiler,environment" :name "keywords"}]
    [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.2.1/styles/github.min.css"}]]
   [:body
    [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.2.1/highlight.min.js" :type "text/javascript"}]
    [:script "hljs.initHighlightingOnLoad();"]
    [:pre [:code {:class "clojure"}
           (with-out-str
             (pprint/pprint manifest))]]]])

(defn render-index-html [{:keys [manifest]} _]
  {:status  200
   :body    (hiccup/html (index-html manifest))
   :headers {"Content-Type" "text/html"}})

(defn routes [ctx]
  [["/" {:get {:handler (partial render-index-html ctx)}}]
   ["/api/:version/manifest.edn" {:get {:handler (partial manifest-resp ctx)}}]
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
                             :endpoint   (:endpoint opts)}}
   :ring/server       {:handler (ig/ref :ring/handler)
                       :port    (:port opts)}})

(defn prod-opts []
  {:region   (System/getenv "S3_REGION")
   :bucket   (System/getenv "S3_BUCKET")
   :port     (Long/parseLong (System/getenv "PORT"))
   :endpoint (System/getenv "DEPSTRAP_ENDPOINT")})

(defn -main [& _]
  (try
    (let [system (ig/init (config (prod-opts)))
          latch  (CountDownLatch. 1)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (.countDown latch))))
      (.await latch)
      (ig/halt! system)
      (System/exit 0))
    (catch Throwable e
      (.printStackTrace e)
      (System/exit 1))))