(ns pg3.master
  (:require [k8s.core :as k8s]
            [unifn.core :as u]))

(defn volume-claims [{db-name :name
                      ns :ns
                      storage-class :storage-class
                      color :color
                      data-size :data-size
                      xlog-size :xlog-size
                      annotations :annotations}]

  [{:kind "PersistentVolumeClaim"
    :ns ns
    :apiVersion "v1"
    :metadata {:name (str db-name "-" color "-data")
               :labels {:component db-name :color color}
               :annotation (merge {} annotations)}
    :spec  {:accessModes ["ReadWriteOnce"]
            :resources {:requests {:storage data-size}}}}

   {:kind "PersistentVolumeClaim"
    :apiVersion "v1"
    :ns ns
    :metadata {:name (str db-name "-" color "-xlog")
               :labels {:component db-name :color color}
               :annotation (merge {} annotations)}
    :spec  {:accessModes ["ReadWriteOnce"]
            :resources {:requests {:storage data-size}}}}])

(k8s/query {:apiVersion "v1"
            :kind "PersistentVolumeClaim"})

(def spec
  {:name "cleo"
   :color "green"
   :data-size "1Gi"
   :xlog-size "1Gi"
   :annotations {"volume.beta.kubernetes.io/storage-class" "standard"}})


(defmethod u/*fn ::create-pvc
  [{spec :spec}]
  (let [cfg      {:prefix "api" :apiVersion "v1" :ns "default"}
        vols     (volume-claims spec)
        pvc-data (k8s/create (first vols))
        pvc-xlog (k8s/create (second vols))]
    [pvc-data pvc-xlog]
    ))


(defmethod u/*fn ::destroy-pvc [{spec :spec}]
  (let [cfg      {:prefix "api" :apiVersion "v1" :ns "default"}
        vols     (volume-claims spec)
        pvc-data (k8s/delete  (first vols))
        pvc-xlog (k8s/delete (second vols))])
  )

(defn vpc-created? [{spec :spec}]

  )

(def state-machine
  {[:tick :init :creating-vpc] {:probe vpc-created?
                                :to :init-cluster}})

(defn reconcile [ev pg state]
  )

(comment
  (k8s/query {:prefix "api"
              :ns "default"
              :apiVersion "v1"}
             "persistentvolumeclaims/cleo-green-data/status")

  (def res (k8s/create (assoc (first (volume-claims spec)) :ns "default")))

  res

  (k8s/create {:prefix "api"
               :apiVersion "v1"
               :ns "default"}
              :persistentvolumeclaims
              (second (volume-claims spec)))
  res

  )
