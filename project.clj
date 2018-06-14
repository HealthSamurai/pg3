(defproject pg3 "0.0.1-SNAPSHOT"
  :description "pg in k8s"
  :url "http://pg.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha16"]
                 [ch.qos.logback/logback-classic "1.2.2"]
                 [clj-json-patch "0.1.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.postgresql/postgresql "9.4.1211.jre7"]
                 [org.clojure/core.async "0.3.443"]
                 [cheshire "5.7.1"]
                 [hiccup "1.0.5"]
                 [route-map "0.0.4"]
                 [morse   "0.2.4"]
                 [pandect "0.6.1"]
                 [ring/ring-defaults "0.3.0"]
                 [http.async.client "1.2.0"]
                 [inflections "0.13.0"]
                 [http-kit "2.2.0"]
                 [clj-yaml "0.4.0"]
                 [clj-jwt "0.1.1"]
                 [clj-time "0.13.0"]
                 [clojure-humanize "0.2.2"]
                 [environ "1.1.0"]
                 [hiccup "1.0.5"]
                 [garden "1.3.2"]
                 [matcho "0.1.0-RC5"]]
  :uberjar-name "pg3.jar"
  :main pg3.core
  :profiles {:dev {:source-paths  ["test" "src"]
                   :plugins [[lein-dotenv "RELEASE"]]}
             :uberjar {:aot :all :omit-source true}})
