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

(defmethod u/*fn ::find-master-instance [{cluster :resource}]
  {::master (first (ut/my-pginstances cluster))})

(defmethod u/*fn ::find-replica-instance [{cluster :resource}]
  {::replica (second (ut/my-pginstances cluster))})

(defmethod u/*fn ::load-random-colors [arg]
  (let [colors (take 2 (shuffle naming/colors))]
    {::master-color (first colors)
     ::replica-color (second colors)}))

(defn strict-patch [resource]
  (let [result (k8s/patch resource)]
    (when (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)})))

(defmethod u/*fn ::ensure-cluster-config [{cluster :resource}]
  (strict-patch (model/config-map cluster)))

(defmethod u/*fn ::ensure-cluster-secret [{cluster :resource}]
  (strict-patch (model/secret cluster)))

(defmethod u/*fn ::ensure-master [{master ::master
                                   cluster :resource
                                   color ::master-color}]
  (let [master (or master (model/instance-spec cluster color "master"))]
    (strict-patch master)))

(defmethod u/*fn ::ensure-replica [{replica ::replica
                                   cluster :resource
                                   color ::replica-color}]
  (let [replica (or replica (model/instance-spec cluster color "replica"))]
    (strict-patch replica)))

(defmethod u/*fn ::finish-init [arg]
  {::u/status :success
   ::u/message "Cluster initialized"})

(def fsm-pg-cluster
  {:init {:action-stack [::ensure-cluster-config
                         ::ensure-cluster-secret
                         ::find-master-instance
                         ::find-replica-instance
                         ::load-random-colors
                         ::ensure-master
                         ::ensure-replica
                         ::finish-init]
          :success :active
          :error :error-state}
   :active {}
   :error-state {}})

(defn watch-clusters []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-pg-cluster cluster)))

(comment
  (watch-clusters))
