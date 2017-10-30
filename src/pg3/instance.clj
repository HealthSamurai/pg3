(ns pg3.instance
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]))

(defn update-status [inst status]
  (k8s/patch
   (assoc inst
          :kind naming/instance-resource-kind
          :apiVersion naming/api
          :status (merge (or (:status inst) {})
                         {:lastUpdate (java.util.Date.)}
                         status))))

(defn init-instance [inst]
  (let [data-v (k8s/patch (model/instance-data-volume-spec inst))
        wals-v (k8s/patch (model/instance-wals-volume-spec inst))]
    (update-status inst {:volumes [data-v wals-v]
                         :phase "waiting-volumes"})))

(defn volumes-ready? [inst]
  (let [vols (get-in inst [:status :volumes])
        ready? (reduce
                (fn [acc v]
                  (let [pvc (k8s/find
                             (assoc v
                                    :kind "PersistentVolumeClaim"
                                    :apiVersion "v1"))]
                    (println "PVC STATUS:" (get-in pvc [:status :phase]))
                    (and acc (= "Bound" (get-in pvc [:status :phase])))))
                true vols)]
    (println "Ready!!" ready?)))

(defn instance-status [inst]
  #_(println "Status " inst))

(defn watch-instance [{st :status :as inst}]
  (cond
    (nil? st) (init-instance inst)
    (= "waiting-volumes" (:phase st)) (volumes-ready? inst)
    :else  (instance-status inst)))

(defn watch-instances []
  (doseq [inst (:items (k8s/query {:kind naming/instance-resource-kind :apiVersion naming/api}))]
    (watch-instance inst)))


(comment

  (watch-instances)

  )
