(ns pg3.naming-test
  (:require [pg3.naming :as sut]
            [clojure.test :refer :all]))

(def cluster {:kind "Pg" :apiVersion "pg3.io/v1" :metadata {:name "mydb"}})
(def instance {:kind "PgInstance"
               :apiVersion "pg3.io/v1"
               :metadata {:name (sut/instance-name cluster "red")
                          :lables (merge
                                   (sut/cluster-labels cluster)
                                   (sut/instance-labels "master" "red"))}})

(deftest naming-test

  (is (= "pg3-mydb" (sut/cluster-name cluster)))
  (is (= "pg3-mydb" (sut/config-map-name cluster)))
  (is (= "pg3-mydb" (sut/secret-name cluster)))
  (is (= "pg3-mydb" (sut/service-name cluster)))

  (is (= "pg3-mydb-red" (sut/instance-name cluster "red")))
  (is (= "pg3-mydb-red-data" (sut/data-volume-name instance)))
  (is (= "pg3-mydb-red-wals" (sut/wals-volume-name instance)))


  (is (= "pg3-mydb-red" (sut/deployment-name instance)))
  (is (= "pg3-mydb-red-init-db" (sut/pod-name instance "init" "db")))




  )

