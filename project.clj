(defproject ci3 "0.0.1-SNAPSHOT"
  :description "minimalistic ci for k8s"
  :url "http://ci3.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha16"]
                 [ch.qos.logback/logback-classic "1.2.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [cheshire "5.7.1"]
                 [clj-json-patch "0.1.4"]
                 [http-kit "2.2.0"]
                 [route-map "0.0.4"]
                 [clj-yaml "0.4.0"]
                 [clj-jwt "0.1.1"]
                 [http.async.client "1.2.0"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.postgresql/postgresql "9.4.1211.jre7"]]
  :uberjar-name "pg3.jar"
  :main ci3.core
  :profiles {:uberjar {:aot :all :omit-source true}})
