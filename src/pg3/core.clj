(ns pg3.core
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [pg3.model :as m]
            [clojure.string :as str]
            [cheshire.core :as json]
            [pg3.cluster :as cluster]
            [pg3.telegram :as telegram]
            [pg3.instance :as instance]
            [pg3.backup :as backup])
  (:gen-class))


(defn watch []
  (cluster/watch)
  (instance/watch)
  (backup/watch))


(defonce server (atom nil))

(defn stop []
  (when-let [thr @server]
    (.interrupt thr)
    (reset! server nil)))

(defn start []
  (stop)
  (k8s/patch m/cluster-definition)
  (k8s/patch m/instance-definition)
  (k8s/patch m/backup-definition)
  (let [thr (Thread.
             (fn []
               (println "Start")
               (try
                 (while (not (Thread/interrupted))
                   (watch)
                   (Thread/sleep 10000))
                 (catch java.lang.InterruptedException e
                   (println "Bay, bay")))))]
    (reset! server thr)
    (.start thr)))

(defn -main []
  (start))
