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

(defn persistent-volume-claim? [res]
  (and (map? res) (= (or (:kind res) (get res "kind")) "PersistentVolumeClaim")))

(defn persistent-volume-claim-patch [spec]
  (let [res (k8s/find spec)]
    (println "!11111111111!" (persistent-volume-claim? res) res)
    (if (persistent-volume-claim? res)
      res
      (k8s/patch spec))))

(defn init-instance-volumes [inst]
  (let [data-v (persistent-volume-claim-patch (model/instance-data-volume-spec inst))
        wals-v (persistent-volume-claim-patch (model/instance-wals-volume-spec inst))]
    (if (every? persistent-volume-claim? [data-v wals-v])
      (do
        (update-status inst {:volumes [data-v wals-v]
                             :phase "waiting-volumes"})
        {:status :ok
         :text "Instance volumes requested"})

      (do
        (update-status inst {:phase "persistent-volume-request-error"})
        {:status :error
         :text (str "Instance volumes request error: " data-v wals-v)}))))

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

(defn find-resource [kind ns res-name]
  (k8s/find {:kind kind
             :apiVersion "v1"
             :metadata {:name res-name
                        :namespace ns}}))

(defn find-initdb-pod [inst]
  (let [pod-name (or (get-in inst [:status :initdbPod])
                     (get-in (model/initdb-pod inst) [:metadata :name]))
        ns (get-in inst [:metadata :namespace])
        res (find-resource "Pod" ns pod-name)]
    (println  "find-initdb-pod" res)
    (when-not (and (= (:code res) 404) (= (:kind res) "Status"))
      res)))

(defn init-instance [inst]
  (if (= "master" (get-in inst [:spec :role]))
    ;; TODO check status
    (if-let [pod (find-initdb-pod inst)]
      (do
        (update-status inst {:phase "waiting-master-initdb"
                             :initdbPod (get-in pod [:metadata :name])})
        {:status :ok
         :text "Master already exists"})
      (let [pod (model/initdb-pod inst)
            res (k8s/create pod)]
        (->  (yaml/generate-string res)
             (println))
        (update-status inst {:phase "waiting-master-initdb"
                             :initdbPod (get-in pod [:metadata :name])})
        {:status :ok
         :text "Master initialize started"}))
    (do 
      (update-status inst {:phase "replica-not-implemented"})
      {:status :ok
       :text "Replica not implemented"})))

(defn master-inited? [inst]
  (let [pod (find-initdb-pod inst)
        phase (get-in pod [:status :phase])]
    (cond
      (= "Succeeded" phase)
      (do
        (update-status inst {:phase "master-ready-to-start"})
        {:status :ok
         :text "Master ready to start"})

      (#{"Pending" "Running"} phase)
      {:status :pending}

      :else
      (do
        (println "Init Db Pod is not success: " pod)
        {:status :error
         :text (str "Init db fail: " (get-in pod [:metadata :name]))}))))

(defn start-master [inst]
  (println "Start master" inst)
  (let [depl-spec (model/master-deployment inst)
        depl (k8s/patch depl-spec)]
    (-> (update-status inst {:phase "master-starting"})
        yaml/generate-string
        println)
    {:status :ok
     :text "Master starting"}))

(defn master-starting [inst]
  (println "master started?" inst)
  (let [deployment-spec (model/master-service inst)
        deployment (k8s/find deployment-spec)
        ready? (every? (partial = "True") (map :status (get-in deployment [:status :conditions])))]
    (if ready?
      (let [service-spec (model/master-service inst)
            service (k8s/patch service-spec)]

        (-> service
            yaml/generate-string
            println)

        (-> (update-status inst {:phase "active"})
            yaml/generate-string
            println)
        {:status :ok
         :text "Master service created. Master started"})
      {:status :error
       :text (str "Some condition in deployment is not true: " (get-in deployment [:metadata :name]))})))

(defn watch-instance [{st :status :as inst}]
  (cond
    (or (nil? st) (= (:phase st) "init-volumes"))
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
