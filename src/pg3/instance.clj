(ns pg3.instance
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]
            [pg3.utils :as ut]
            [unifn.core :as u]))

(defn update-status [inst status]
  (k8s/patch
   (assoc inst
          :kind naming/instance-resource-kind
          :apiVersion naming/api
          :status (merge (or (:status inst) {})
                         {:lastUpdate (java.util.Date.)}
                         status))))

(defn init-instance-volumes [inst]
  (let [data-v (k8s/patch (model/instance-data-volume-spec inst))
        wals-v (k8s/patch (model/instance-wals-volume-spec inst))]
    (update-status inst {:volumes [data-v wals-v]
                         :phase "waiting-volumes"}))
  {:status :ok
   :text "Instance volumes requested"})

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
    (when ready?
      (update-status inst {:phase "waiting-init"}))
    {:status :ok
     :text (if ready? "Instance volumes ready" "Waiting instance volumes")}))


(defn instance-status [inst]
  (println "DEFAULT: "
           (get-in inst [:metadata :name])
           " "
           (get-in inst [:status :phase])))

(defn init-instance [inst]
  (if (= "master" (get-in inst [:spec :role]))
    ;; TODO check status
    (let [pod (model/initdb-pod inst)
          res (k8s/create pod)]
      (->  (yaml/generate-string res)
           (println))
      (update-status inst {:phase "waiting-master-initdb"
                           :initdbPod (get-in pod [:metadata :name])})
      {:status :ok
       :text "Master initialize started"})
    (do 
      (update-status inst {:phase "replica-not-implemented"})
      {:status :ok
       :text "Replica not implemented"})))

(defn master-inited? [inst]
  (let [pod-name (or (get-in inst [:status :initdbPod])
                     (get-in (model/initdb-pod inst) [:metadata :name]))
        pod (k8s/find {:kind "Pod"
                       :apiVersion "v1"
                       :metadata {:name pod-name
                                  :namespace (get-in inst [:metadata :namespace])}})
        phase (get-in pod [:status :phase])]
    (cond
      (= "Succeeded" phase)
      (do
        (update-status inst {:phase "master-ready-to-start"})
        {:status :ok
         :text "Master ready to start"})

      :else
      (do
        (println "Init Db Pod is not success: " pod)
        {:status :ok
         :text (str "Init db fail: " pod-name)}))))

(defn start-master [inst]
  (println "Start master" inst)
  (let [depl-spec (model/master-deployment inst)
        depl (k8s/create depl-spec)]
    (-> (update-status inst {:phase "master-starting"})
        yaml/generate-string
        println)
    {:status :ok
     :text "Master starting"}))

(defn master-starting [inst]
  ;; TODO check deployment status
  (println "master started?" inst)
  (let [service-spec (model/master-service inst)
        service (k8s/patch service-spec)]

    (-> service
        yaml/generate-string
        println)

    (-> (update-status inst {:phase "active"})
        yaml/generate-string
        println)
    {:status :ok
     :text "Master service created"}))

(defn watch-instance [{st :status :as inst}]
  (cond
    (nil? st)
    (ut/exec-phase "init-volumes" init-instance-volumes inst)

    (= "waiting-volumes" (:phase st))
    (ut/exec-phase (:phase st) volumes-ready? inst)

    (= "waiting-init" (:phase st))
    (ut/exec-phase (:phase st) init-instance inst)

    (= "waiting-master-initdb" (:phase st))
    (ut/exec-phase (:phase st) master-inited? inst)

    (= "master-ready-to-start" (:phase st))
    (ut/exec-phase (:phase st) start-master inst)

    (= "master-starting" (:phase st))
    (ut/exec-phase (:phase st) master-starting inst)

    :else
    (instance-status inst)))

(defn watch-instances []
  (doseq [inst (:items (k8s/query {:kind naming/instance-resource-kind :apiVersion naming/api}))]
    (watch-instance inst)))


(comment

  (watch-instances)

  )
