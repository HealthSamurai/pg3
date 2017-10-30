(ns pg3.model-test
  (:require [pg3.model :as sut]
            [matcho.core :as matcho]
            [clojure.test :refer :all]))


(def cluster
  {:kind "Pg" :apiVersion "pg3.io/v1"
   :metadata {:name "mydb"}
   :spec {:image "aidbox/aidboxdb"
          :version "passive"
          :size "33Gi"
          :storageClass "standard"
          :replicas {:sync 1}}
   :config {:config {:max_connections 777
                     :shared_preload_libraries "pg_pathman"}}})

(def ns-cluster
  {:kind "Pg" :apiVersion "pg3.io/v1"
   :metadata {:name "mydb" :namespace "dbs"}
   :spec {:image "aidbox/aidboxdb"
          :version "passive"
          :size "44Gi"
          :storageClass "non-standard"
          :replicas {:sync 1}}})

(deftest test-model
  (def instance-1 (sut/instance-spec cluster "red" "master"))
  (def ns-instance-1 (sut/instance-spec ns-cluster "blue" "master"))

  (matcho/match
   instance-1
   {:kind "PgInstance"
    :apiVersion "pg3.io/v1"
    :metadata
    {:name "pg3-mydb-red"
     :namespace "default"
     :labels
     {:system "pg3"
      :service "pg3-mydb"
      :pgrole "master"
      :color "red"}}
    :spec {:pg "mydb" :role "master" :storageClass "standard"}})

  (matcho/match
   (sut/instance-spec ns-cluster "red" "master")
   {:kind "PgInstance"
    :metadata
    {:name "pg3-mydb-red"
     :namespace "dbs"}
    :spec {:pg "mydb" :role "master" :storageClass "non-standard"}})

  (matcho/match
   (sut/instance-data-volume-spec instance-1)
   {:kind "PersistentVolumeClaim"
    :apiVersion "v1"
    :metadata {:name "pg3-mydb-red-data"
               :lables {:system "pg3"
                        :service "pg3-mydb"
                        :pgrole "data"
                        :color "red"}
               :namespace "default"
               :annotations {"volume.beta.kubernetes.io/storage-class" "standard"}}
    :spec {:accessModes ["ReadWriteOnce"],
           :resources {:requests {:storage "33Gi"}}}})

  (matcho/match
   (sut/instance-wals-volume-spec instance-1)
   {:kind "PersistentVolumeClaim"
    :apiVersion "v1"
    :metadata {:name "pg3-mydb-red-wals"
               :lables {:system "pg3"
                        :service "pg3-mydb"
                        :pgrole "data"
                        :color "red"}
               :namespace "default"
               :annotations {"volume.beta.kubernetes.io/storage-class" "standard"}}
    :spec {:accessModes ["ReadWriteOnce"],
           :resources {:requests {:storage "33Gi"}}}})

  (matcho/match
   (sut/instance-data-volume-spec ns-instance-1)
   {:kind "PersistentVolumeClaim"
    :apiVersion "v1"
    :metadata {:name "pg3-mydb-blue-data"
               :lables {:system "pg3"
                        :service "pg3-mydb"
                        :pgrole "data"
                        :color "blue"}
               :namespace "dbs"
               :annotations {"volume.beta.kubernetes.io/storage-class" "non-standard"}}
    :spec {:accessModes ["ReadWriteOnce"],
           :resources {:requests {:storage "44Gi"}}}})

  (matcho/match
   (sut/instance-wals-volume-spec ns-instance-1)
   {:kind "PersistentVolumeClaim"
    :apiVersion "v1"
    :metadata {:name "pg3-mydb-blue-wals"
               :lables {:system "pg3"
                        :service "pg3-mydb"
                        :pgrole "data"
                        :color "blue"}
               :namespace "dbs"
               :annotations {"volume.beta.kubernetes.io/storage-class" "non-standard"}}
    :spec {:accessModes ["ReadWriteOnce"],
           :resources {:requests {:storage "44Gi"}}}})


  (is (re-find #"max_connections = 777" (sut/pg-config cluster)))
  (is (re-find #"pg_pathman" (sut/pg-config cluster)))



  )


