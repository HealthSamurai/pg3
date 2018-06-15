(ns pg3.core
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [pg3.model :as m]
            [clojure.string :as str]
            [cheshire.core :as json]
            [pg3.cluster :as cluster]
            [pg3.telegram :as telegram]
            [pg3.instance :as instance])
  (:gen-class))


(defn watch []
  (cluster/watch-clusters)
  (instance/watch-instances))


(defonce server (atom nil))

(defn stop []
  (when-let [thr @server]
    (.interrupt thr)
    (reset! server nil)))

(defn start []
  (stop)
  (telegram/start)
  (k8s/patch m/cluster-definition)
  (k8s/patch m/instance-definition)
  (let [thr (Thread.
             (fn []
               (println "Start")
               (try
                 (while (not (Thread/interrupted))
                   (watch)
                   (Thread/sleep 10000))
                 (catch java.lang.InterruptedException e
                   (telegram/stop)
                   (println "Bay, bay")))))]
    (reset! server thr)
    (.start thr)))

(defn -main []
  (start))
