(defproject cljspad/webjars-server "0.1.0"
  :source-paths ["src"]
  :dependencies [[thheller/shadow-cljs "2.11.4"]
                 [com.cognitect.aws/api "0.8.474"]
                 [com.cognitect.aws/endpoints "1.1.11.842"]
                 [com.cognitect.aws/s3 "809.2.734.0"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [integrant "0.8.0"]
                 [ring "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [metosin/reitit-core "0.5.5"]
                 [metosin/reitit-ring "0.5.5"]
                 [clj-http "3.10.3"]
                 [ring-cors "0.1.13"]])