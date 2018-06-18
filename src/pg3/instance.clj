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

(defn pvc? [res]
  (and (map? res) (= (:kind res) "PersistentVolumeClaim")))

(defn pvc-patch [spec]
  (let [res (k8s/find spec)]
    (if (pvc? res)
      res
      (k8s/patch spec))))

(defmethod u/*fn ::init-instance-volumes [{inst :resource}]
  (let [data-v (pvc-patch (model/instance-data-volume-spec inst))
        wals-v (pvc-patch (model/instance-wals-volume-spec inst))]
    (if (every? pvc? [data-v wals-v])
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

(defn find-init-pod [inst init-pod-fn]
  (let [pod-name (or (get-in inst [:status :initPod])
                     (get-in (init-pod-fn inst) [:metadata :name]))
        ns (get-in inst [:metadata :namespace])
        res (find-resource "Pod" ns pod-name)]
    (when-not (and (= (:code res) 404) (= (:kind res) "Status"))
      res)))

(defn instance-role [inst]
  (str/capitalize (get-in inst [:spec :role])))

(defmethod u/*fn ::init-instance [{instance :resource
                                   init-pod-fn ::init-pod-fn}]
  (let [role (instance-role instance)]
    (if-let [pod (find-init-pod instance init-pod-fn)]
      {::u/status :success
       ::u/message (str role " already exists")
       :initPod (get-in pod [:metadata :name])}
      (let [pod (init-pod-fn instance)
            res (k8s/create pod)]
        (if (= (:kind res) "Status")
          {::u/status :error
           ::u/message (str res)}
          {::u/status :success
           ::u/message (str role " initialize started")
           :status-data {:initPod (get-in pod [:metadata :name])}})))))

(defmethod u/*fn ::instance-inited? [{inst :resource
                                      init-pod-fn ::init-pod-fn}]
  (let [pod (find-init-pod inst init-pod-fn)
        phase (get-in pod [:status :phase])
        role (instance-role inst)]
    (cond
      (= "Succeeded" phase)
      {::u/status :success
       ::u/message (str role " ready to start")}

      (#{"Pending" "Running"} phase)
      {::u/status :pending}

      :else
      {::u/status :error
       ::u/message (str role " init fail: " (get-in pod [:metadata :name]))})))

(defmethod u/*fn ::start-instance [{inst :resource}]
  (let [role (instance-role inst)
        res (k8s/patch (model/postgres-deployment inst))]
    (if (= (:kind res) "Status")
      {::u/status :error
       ::u/message (str res)}
      {::u/status :success
       ::u/message (str role " starting")})))

(defn deployment-success? [deployment]
  (every? (partial = "True") (map :status (get-in deployment [:status :conditions]))))

(defmethod u/*fn ::instance-started? [{inst :resource}]
  (let [deployment (k8s/find (model/postgres-deployment inst))]
    (when-not (deployment-success? deployment)
      {::u/status :error
       ::u/message (str "Postgres deployment fail: " (get-in deployment [:metadata :name]))})))

(defmethod u/*fn ::start-instance-service [{inst :resource
                                            service-fn ::service-fn}]
  (let [role (instance-role inst)
        res (k8s/patch (service-fn inst))]
    (if (= (:kind res) "Status")
      {::u/status :error
       ::u/message (str res)}
      {::u/status :success
       ::u/message (format "%s service created. %s started" role role)})))

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
   {:waiting-init {:action-stack [{::u/fn ::init-instance
                                   ::init-pod-fn model/init-master-pod}]
                   :success :waiting-master-init-pod
                   :error :error-state}

    :waiting-master-init-pod {:action-stack [{::u/fn ::instance-inited?
                                              ::init-pod-fn model/init-master-pod}]
                              :success :master-ready-to-start
                              :error :error-state}

    :master-ready-to-start {:action-stack [::start-instance]
                            :success :master-starting
                            :error :error-state}

    :master-starting {:action-stack [::instance-inited?
                                     {::u/fn ::start-instance-service
                                      ::service-fn model/master-service}]
                      :success :active
                      :error :error-state}

    :active {}}))

(defmethod u/*fn ::find-master-instance [{replica :resource}]
  (let [cluster-name (get-in replica [:spec :pg-cluster])
        ns (get-in replica [:metadata :namespace])
        cluster {:metadata {:namespace ns
                            :name cluster-name}}
        master (:master (ut/my-pginstances cluster))]
    {::master master}))

(defmethod u/*fn ::wait-master [{master ::master}]
  (if (= (get-in master [:status :phase]) "active")
    {::u/status :success
     ::u/message "Master was started"}
    {::u/status :pending}))

(def fsm-replica
  (merge
   fsm-base
   {:waiting-init {:action-stack [::find-master-instance
                                  ::wait-master]
                   :success :init-replica-instance
                   :error :error-state}
    :init-replica-instance {:action-stack [{::u/fn ::init-instance
                                            ::init-pod-fn model/init-replica-pod}]
                            :success :waiting-replica-init
                            :error :error-state}
    :waiting-replica-init {:action-stack [{::u/fn ::instance-inited?
                                           ::init-pod-fn model/init-replica-pod}]
                           :success :replica-ready-to-start
                           :error :error-state}
    :replica-ready-to-start {:action-stack [::start-instance]
                             :success :replica-starting
                             :error :error-state}
    :replica-starting {:action-stack [::instance-inited?
                                      {::u/fn ::start-instance-service
                                       ::service-fn model/replica-service}]
                       :success :active
                       :error :error-state}
    :active {}}))

(defn watch-instances []
  (doseq [inst (:items (k8s/query {:kind naming/instance-resource-kind :apiVersion naming/api}))]
    (let [fsm* (if (= "master" (get-in inst [:spec :role])) fsm-master fsm-replica)]
      (fsm/process-state fsm* inst))))


(comment

  (watch-instances)

  )
