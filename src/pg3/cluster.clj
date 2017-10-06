(ns pg3.cluster
  (:require [k8s.core :as k8s]))

;; 1. create volume for master
;; 2. create config map for db
;; 3. create cluster and set admin password
;; 4. start master db

;; 1. craete volume for slave
;; 2. get basebackup
;; 3. create replication slot
;; 4. start slave



;; (require '[clj-yaml.core :as yaml])

;; (k8s/query {:prefix "api"
;;             :apiVersion "v1"}
;;            :persistentvolumes)

;; (k8s/query {:prefix "api"
;;             :ns "pg3"
;;             :apiVersion "v1"}
;;            "persistentvolumeclaims/cleo-green-data/status")

;; (def spec
;;   {:ns "pg3"
;;    :name "cleo"
;;    :storage {:size "1Gi"
;;              :class "standard"}})

;; (def colors ["green" "blue" "pink" "yellow"])

;; (defn data-volume [s color]
;;   {:kind "PersistentVolumeClaim"
;;    :apiVersion "v1"
;;    :metadata {:name (str (:name s) "-" color "-data") 
;;               :labels {:pgcluster (:name s)}
;;               :annotations {"volume.beta.kubernetes.io/storage-class" (get-in s [:storage :class])}}
;;    :spec {:accessModes ["ReadWriteOnce"]
;;           :resources {:requests {:storage  (get-in s [:storage :size])}}}})

;; (data-volume spec (first colors))

;; #_(def res
;;   (k8s/create {:prefix "api"
;;                :apiVersion "v1"
;;                :ns (:ns spec)}
;;               :persistentvolumeclaims
;;               (data-volume spec (first colors))))

;; (k8s/find {:prefix "api"
;;            :apiVersion "v1"
;;            :ns (:ns spec)}
;;           :persistentvolumeclaims
;;           (get-in (data-volume spec (first colors)) [:metadata :name]))
