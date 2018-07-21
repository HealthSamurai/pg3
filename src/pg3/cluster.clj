(ns pg3.cluster
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]
            [pg3.utils :as ut]
            [pg3.fsm :as fsm]
            [unifn.core :as u]))

;; watch new clusters
;; orchestarate setup

;; watch status of clusters
;; based on instances state

(defmethod u/*fn ::load-pg-instances [{cluster :resource}]
  {::ut/pginstances (ut/pginstances (get-in cluster [:metadata :namespace]) (naming/service-name (naming/resource-name cluster)))})

(defmethod u/*fn ::load-random-colors [arg]
  (let [colors (take 2 (shuffle naming/colors))]
    {::colors {:master (first colors)
               :replica (second colors)}}))

#_(defmethod u/*fn :k8s/patch [{path :k8s/path :as arg}]
    (let [result (k8s/patch (get-in arg path))]
      (when (= (:kind result) "Status")
        {::u/status :error
         ::u/message (str result)})))

#_(defmethod u/*fn :k8s/create [{path :k8s/path :as arg}])

(defn strict-patch [resource]
  (let [result (k8s/patch resource)]
    (when (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)})))

(defmethod u/*fn ::ensure-cluster-config [{cluster :resource}]
  (strict-patch (model/config-map cluster)))

(defmethod u/*fn ::ensure-cluster-secret [{cluster :resource}]
  (strict-patch (model/secret cluster)))

(defmethod u/*fn ::ensure-cluster-backup-item [{cluster :resource backup-item ::backup-item}]
  (strict-patch (model/backup-spec cluster backup-item)))

(defmethod u/*fn ::ensure-cluster-backup [{cluster :resource :as arg}]
  (dissoc
   (u/*apply (->> (:backup cluster)
                  (mapv (fn [item] {::u/fn ::ensure-cluster-backup-item
                                    ::backup-item item})))
             arg)
   ::backup-item))

(defmethod u/*fn ::ensure-pg-instances [{cluster :resource pg-instances ::ut/pginstances}]
  (let [all-pg-instances (:all pg-instances)
        master (:master pg-instances)
        replicas (:replicas pg-instances)
        desired-sync-replica-count (get-in cluster [:spec :replicas :sync] 0)
        desired-async-replica-count (get-in cluster [:spec :replicas :async] 0)
        desired-replica-count (+ desired-sync-replica-count desired-async-replica-count)
        lack-replica-count (- desired-replica-count (count replicas))
        odd-replica-count (- lack-replica-count)
        reserved-colors (mapv #(get-in % [:metadata :labels :color]) all-pg-instances)
        free-colors (clojure.set/difference (set naming/colors) reserved-colors)
        colors (take lack-replica-count free-colors)]
    (if-not master
      (strict-patch (model/instance-spec cluster "master" "master" {:slots (apply conj reserved-colors colors)}))) ;; fixme: use color too
    (if (> odd-replica-count 0) nil) ;; fixme: delete odd pg-instances
    (doall (map #(strict-patch (model/instance-spec cluster % "replica")) colors))))

(defmethod u/*fn ::ensure-instance [{role ::role cluster :resource :as arg}]
  (let [instance (get-in arg [::ut/pginstances role])
        color (get-in arg [::colors role])
        instance (or instance (model/instance-spec cluster color (name role)))]
    (strict-patch instance)))

(defn load-pods [cluster]
  (let [ns (get-in cluster [:metadata :namespace])
        service-name (naming/service-name (naming/resource-name cluster))
        pods (:items (k8s/query {:apiVersion "v1"
                                 :kind "pod"
                                 :ns ns}
                                {:labelSelector (format "service=%s,type=instance" service-name)}))]
    (->> pods
         (map (fn [pod]
                [(keyword (get-in pod [:metadata :labels :role])) pod]))
         (into {}))))

(def fsm-main
  {:init {:action-stack [{::u/fn ::ut/success
                          ::ut/message "Starting initialization..."}]
          :success :start-init}
   :start-init {:action-stack [::ensure-cluster-config
                               ::ensure-cluster-secret
                               ::ensure-cluster-backup
                               ::load-pg-instances
                               ::ensure-pg-instances
                               {::u/fn ::ut/success}]
                :success :waiting-initialization
                :error :error-state}
   :waiting-initialization {:action-stack [::load-pg-instances
                                           {::u/fn ::ut/cluster-active?}
                                           {::u/fn ::ut/success
                                            ::ut/message "Cluster was successfully initialized. Cluster is active..."}]
                            :success :active
                            :error :error-state}
   :active {:action-stack [::ensure-cluster-config
                           ::ensure-pg-instances]
                :success :active
                :error :error-state}
   :error-state {}})

(defn watch []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-main cluster)))

(comment
  (watch))
