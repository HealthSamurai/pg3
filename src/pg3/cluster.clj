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
             (get-in replica [:status :phase])
             "active")
      {::u/status :success
       ::u/message "Cluster is active"})))

(defn load-pods [cluster]
  (let [ns (get-in cluster [:metadata :namespace])
        cluster-name (naming/cluster-name cluster)
        pods (:items (k8s/query {:apiVersion "v1"
                                 :kind "pod"
                                 :ns ns}
                                {:labelSelector (format "service=%s,type=instance" cluster-name)}))]
    (->> pods
         (map (fn [pod]
                [(keyword (get-in pod [:metadata :labels :role])) pod]))
         (into {}))))

(defmethod u/*fn ::load-pods [{cluster :resource}]
  {::pods (load-pods cluster)})

(defmethod u/*fn ::pod-running? [{role ::role pods ::pods errors ::errors :or {errors []}}]
  (if-let [pod (get pods role)]
    {::pod pod}
    {::errors (conj errors (str (str/capitalize (name role)) " • Pod is not running"))}))

(defmethod u/*fn ::check-instance-disk [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "/bin/bash"
               :args ["-c" "df -h /data --output=pcent | grep -P -o \\\\d+"]}
          {status :status message :message} (k8s/exec pod cmd)
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (cond
        (and (= status :succeed) (>= (ut/read-int message) 90))
        {::errors (conj errors (format "%s • Low disk space: %d%%" role (ut/read-int message)))}

        (= status :failure)
        {::errors (conj errors (format "%s • %s" role message))}))))

(defmethod u/*fn ::check-postgres [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "psql"
               :args ["-c" "select 1;"]}
          {status :status message :message} (k8s/exec pod cmd)
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (when (= status :failure)
        {::errors (conj errors (format "%s • Postgresql not available: %s" role message))}))))

(defmethod u/*fn ::check-replication-status [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "psql"
               :args ["-qtAX" "-c" "select count(*) from pg_stat_replication;"]}
          {status :status message :message} (k8s/exec pod cmd)
          _ (println status message)
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (cond (and (= status :succeed) (< (ut/read-int message) 1))
            {::errors (conj errors (format "%s • has no connected replicas" role))}
            (= status :failure)
            {::errors (conj errors (format "%s • %s" role message))}))))

(defmethod u/*fn ::calculate-monitoring-result [{errors ::errors}]
  (when-not (empty? errors)
    {::u/status :error
     ::u/message (str "\n" (str/join "\n" errors))}))

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
   :monitoring {:action-stack [::load-pods
                               {::u/fn ::pod-running? ::role :master}
                               {::u/fn ::check-instance-disk}
                               {::u/fn ::check-postgres}
                               {::u/fn ::check-replication-status}
                               {::u/fn ::pod-running? ::role :replica}
                               {::u/fn ::check-instance-disk}
                               {::u/fn ::check-postgres}
                               ::calculate-monitoring-result
                               #_::check-replication]
                :error :monitoring}
   :error-state {}})

(defn watch-clusters []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-pg-cluster cluster)))

(comment
  (watch-clusters))
