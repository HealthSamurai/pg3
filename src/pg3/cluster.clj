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

(defmethod u/*fn ::load-instances [{cluster :resource}]
  {::instances (ut/my-pginstances cluster)})

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

(defmethod u/*fn ::ensure-instance [{role ::role cluster :resource :as arg}]
  (let [instance (get-in arg [::instances role])
        color (get-in arg [::colors role])
        instance (or instance (model/instance-spec cluster color (name role)))]
    (strict-patch instance)))

(defmethod u/*fn ::finish-init [arg]
  {::u/status :success
   ::u/message "Cluster initialized"})

(defmethod u/*fn ::instances-active? [{cluster :resource}]
  (let [{master :master replica :replica} (ut/my-pginstances cluster)]
    (when (= (get-in master [:status :phase])
             (get-in master [:status :phase])
             "active")
      {::u/status :success
       ::u/message "Cluster is active"})))

(defmethod u/*fn ::check-instance-disk [{cluster :resource role ::role}]
  (let [inst (get (ut/my-pginstances cluster) role)
        ns (get-in cluster [:metadata :namespace])
        color (get-in inst [:metadata :labels :color])
        cluster-name (naming/cluster-name cluster)
        pod (first (:items (k8s/query {:apiVersion "v1"
                                       :kind "pod"
                                       :ns ns
                                       :labelSelector [(str "color=" color)
                                                       (str "service=" cluster-name)
                                                       (str "role=" role)]})))]
    (if pod
      (let [_ (clojure.pprint/pprint pod)
            {status :status
             message :message} (k8s/exec (assoc pod
                                                :apiVersion "v1"
                                                :kind "pod") "df -h /data")]
        {::u/status (if (= status :succeed) :success :error)
         ::u/message message})
      {::u/status :error
       ::u/message (str (str/capitalize (name role)) " pod is not running")})))

(def fsm-pg-cluster
  {:init {:action-stack [::ensure-cluster-config
                         ::ensure-cluster-secret
                         ::load-instances
                         ::load-random-colors
                         {::u/fn ::ensure-instance ::role :master}
                         {::u/fn ::ensure-instance ::role :replica}
                         ::finish-init]
          :success :waiting-initialization
          :error :error-state}
   :waiting-initialization {:action-stack [::instances-active?]
                            :success :monitoring
                            :error :error-state}
   :monitoring {:action-stack [{::u/fn ::check-instance-disk
                                ::role :master}
                               {::u/fn ::check-instance-disk
                                ::role :replica}
                               #_::check-replication]
                ;; :error :monitoring
                :error :error-state
                }
   :error-state {}})

(defn watch-clusters []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-pg-cluster cluster)))

(comment
  (watch-clusters))
