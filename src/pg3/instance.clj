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
       :status-data {:volumes [data-v wals-v]}}
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
    (when-not ready?
      {::u/status :stop})))

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
       :initPod (get-in pod [:metadata :name])}
      (let [pod (init-pod-fn instance)
            res (k8s/create pod)]
        (if (= (:kind res) "Status")
          {::u/status :error
           ::u/message (str res)}
          {::u/status :success
           ::u/message (str "Starting initialization as " role "...")
           :status-data {:initPod (get-in pod [:metadata :name])}})))))

(defmethod u/*fn ::instance-inited? [{inst :resource
                                      init-pod-fn ::init-pod-fn}]
  (let [pod (find-init-pod inst init-pod-fn)
        phase (get-in pod [:status :phase])
        role (instance-role inst)]
    (cond
      (= "Succeeded" phase)
      {}

      (#{"Pending" "Running"} phase)
      {::u/status :stop}

      :else
      {::u/status :error
       ::u/message (str role " init fail: " (get-in pod [:metadata :name]))})))

(defmethod u/*fn ::instance-initialization-completed  [{inst :resource}]
  (let [role (instance-role inst)]
    {::u/status :success
     ::u/message (str "Instance was initialized as " role)}))

(defmethod u/*fn ::start-instance [{inst :resource}]
  (let [role (instance-role inst)
        res (k8s/patch (model/postgres-deployment inst))]
    (if (= (:kind res) "Status")
      {::u/status :error
       ::u/message (str res)}
      {::u/status :success})))

(defmethod u/*fn ::instance-started? [{inst :resource}]
  (let [deployment (k8s/find (model/postgres-deployment inst))]
    (when-not (ut/resource-ok? deployment)
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
       ::u/message (str "Service for " role " was created")})))

(defmethod u/*fn ::load-instance-pod [{inst :resource}]
  #_(clojure.pprint/pprint inst)
  (let [service (get-in inst [:metadata :labels :service])
        color (get-in inst [:metadata :labels :color])]
    {:pod (->
           (k8s/query {:apiVersion "v1"
                       :kind "Pod"
                       :ns "pg3"}
                      {:labelSelector (format "type=instance,service=%s,color=%s" service color)})
           :items
           first)}))

(defmethod u/*fn ::ensure-replication-slots [{pod :pod inst :resource}]
  (let [slots (-> inst :spec :replication :slots)
        script (format "%s/ensure-replication-slots.sh" naming/config-path)
        cmd {:executable "bash"
             :args (apply conj [script] slots)}]
    (clojure.pprint/pprint cmd)
    (when pod
      (let [{status :status message :message} (k8s/exec pod cmd "pg")]
        (println status)
        (println message)))))

(def fsm-base
  {:init {:action-stack [{::u/fn ::ut/success
                          ::ut/message "Initializing volumes..."}]
          :success :start-init}
   :start-init {:action-stack [::init-instance-volumes]
                :success :waiting-volumes
                :error :error-state}

   :waiting-volumes {:action-stack [::volumes-ready?
                                    {::u/fn ::ut/success
                                     ::ut/message "Volumes are ready"}]
                     :success :waiting-init
                     :error :error-state}
   :active {:action-stack [::load-instance-pod
                           ::ensure-replication-slots]
            :success :active
            :error :active}
   :error-state {}})

(def fsm-master
  (merge
   fsm-base
   {:waiting-init {:action-stack [{::u/fn ::init-instance
                                   ::init-pod-fn model/init-master-pod}]
                   :success :waiting-master-init-pod
                   :error :error-state}

    :waiting-master-init-pod {:action-stack [{::u/fn ::instance-inited?
                                              ::init-pod-fn model/init-master-pod}
                                             ::instance-initialization-completed]
                              :success :master-ready-to-start
                              :error :error-state}

    :master-ready-to-start {:action-stack [::start-instance]
                            :success :master-starting
                            :error :error-state}

    :master-starting {:action-stack [::instance-inited?
                                     {::u/fn ::start-instance-service
                                      ::service-fn model/master-service}]
                      :success :active
                      :error :error-state}}))

(defmethod u/*fn ::find-master-instance [{replica :resource}]
  (let [cluster-name (get-in replica [:spec :pg-cluster])
        ns (get-in replica [:metadata :namespace])
        cluster {:metadata {:namespace ns
                            :name cluster-name}}
        master (:master
                (ut/pginstances (get-in cluster [:metadata :namespace]) (naming/service-name (naming/resource-name cluster))))]
    {::master master}))

(defmethod u/*fn ::master-ready? [{master ::master}]
  (when-not (= (get-in master [:status :phase]) "active")
    {::u/status :stop}))

(def fsm-replica
  (merge
   fsm-base
   {:waiting-init {:action-stack [::find-master-instance
                                  ::master-ready?
                                  {::u/fn ::ut/success}]
                   :success :init-replica-instance
                   :error :error-state}
    :init-replica-instance {:action-stack [{::u/fn ::init-instance
                                            ::init-pod-fn model/init-replica-pod}]
                            :success :waiting-replica-init
                            :error :error-state}
    :waiting-replica-init {:action-stack [{::u/fn ::instance-inited?
                                           ::init-pod-fn model/init-replica-pod}
                                          ::instance-initialization-completed]
                           :success :replica-ready-to-start
                           :error :error-state}
    :replica-ready-to-start {:action-stack [::start-instance]
                             :success :replica-starting
                             :error :error-state}
    :replica-starting {:action-stack [::instance-inited?
                                      {::u/fn ::start-instance-service
                                       ::service-fn model/replica-service}]
                       :success :active
                       :error :error-state}}))

(defn watch []
  (doseq [inst (:items (k8s/query {:kind naming/instance-resource-kind :apiVersion naming/api}))]
    (let [fsm* (if (= "master" (get-in inst [:spec :role])) fsm-master fsm-replica)]
      (fsm/process-state fsm* inst))))


(comment
  (watch))
