(ns pg3.instance
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]
            [pg3.utils :as ut]
            [pg3.fsm :as fsm]
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
    (if (persistent-volume-claim? res)
      res
      (k8s/patch spec))))

(defmethod u/*fn ::init-instance-volumes [{inst :resource}]
  (let [data-v (persistent-volume-claim-patch (model/instance-data-volume-spec inst))
        wals-v (persistent-volume-claim-patch (model/instance-wals-volume-spec inst))]
    (if (every? persistent-volume-claim? [data-v wals-v])
      {::u/status :success
       :volumes [data-v wals-v]
       ::u/message "Instance volumes requested"}
      {::u/status :error
       ::u/message (str "Instance volumes request error: " data-v wals-v)})))

(defmethod u/*fn ::volumes-ready? [{inst :resource}]
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
    (if ready?
      {::u/status :success
       ::u/message "Instance volumes ready"}
      ;; TODO: maybe add one more status for sending telegram message
      {::u/status :pending})))

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

(defmethod u/*fn ::init-master-instance [{inst :resource}]
  ;; TODO check status
  (if-let [pod (find-initdb-pod inst)]
    {::u/status :success
     ::u/message "Master already exists"
     :initdbPod (get-in pod [:metadata :name])}
    (let [pod (model/initdb-pod inst)
          res (k8s/create pod)]
      (->  (yaml/generate-string res)
           (println))
      {::u/status :success
       ::u/message "Master initialize started"
       :initdbPod (get-in pod [:metadata :name])})))

(defmethod u/*fn ::init-replica-instance [_]
  {::u/status :success
   ::u/message "Replica not implemented"})

(defmethod u/*fn ::master-inited? [{inst :resource}]
  (let [pod (find-initdb-pod inst)
        phase (get-in pod [:status :phase])]
    (cond
      (= "Succeeded" phase)
      {::u/status :success
       ::u/message "Master ready to start"}

      (#{"Pending" "Running"} phase)
      {::u/status :pending}

      :else
      (do
        (println "Init Db Pod is not success: " pod)
        {::u/status :error
         ::u/message (str "Init db fail: " (get-in pod [:metadata :name]))}))))

(defmethod u/*fn ::start-master [{inst :resource}]
  (println "Start master" inst)
  (let [depl-spec (model/master-deployment inst)
        depl (k8s/patch depl-spec)]
    {::u/status :success
     ::u/message "Master starting"}))

(defmethod u/*fn ::is-master-started [{inst :resource}]
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
        {::u/status :success
         ::u/message "Master service created. Master started"})
      {::u/status :error
       ::u/message (str "Some condition in deployment is not true: " (get-in deployment [:metadata :name]))})))

(def fsm-base
  {:init {:action-stack [::init-instance-volumes]
          :success :waiting-volumes
          :error :error-state}

   :waiting-volumes {:action-stack [::volumes-ready?]
                     :success :waiting-init
                     :error :error-state}

   ;; TODO: make error handling
   :error-state {}})

(def fsm-master
  (merge
   fsm-base
   {:waiting-init {:action-stack [::init-master-instance]
                   :success :waiting-master-initdb
                   :error :error-state}

    :waiting-master-initdb {:action-stack [::master-inited?]
                            :success :master-ready-to-start
                            :error :error-state}

    :master-ready-to-start {:action-stack [::start-master]
                            :success :master-starting
                            :error :error-state}

    :master-starting {:action-stack [::is-master-started]
                      :success :active
                      :error :error-state}

    :active {}}))

(def fsm-replica
  (merge
   fsm-base
   {:waiting-init {:action-stack [::init-replica-instance]
                   :success :replicat-not-implemented
                   :error :error-state}

    :replicat-not-implemented {}}))

;; if status :ok then update-status with returned data and go to next step

#_(defn watch-instance [{st :status :as inst}]
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
    (let [fsm* (if (= "master" (get-in inst [:spec :role])) fsm-master fsm-replica)]
      (fsm/process-state fsm* inst))))


(comment

  (watch-instances)

  )
