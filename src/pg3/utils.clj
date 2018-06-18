(ns pg3.utils
  (:require [k8s.core :as k8s]
            [pg3.naming :as naming]))

(defn find-pginstance-by-role [instances role]
  (first (filter #(= role (get-in % [:spec :role])) instances)))

(defn my-pginstances [cluster]
  (let [pginstances (:items (k8s/query {:kind naming/instance-resource-kind
                                        :ns (get-in cluster [:metadata :namespace])
                                        :apiVersion naming/api}
                                       {:labelSelector
                                        (format "service in (%s)" (naming/cluster-name cluster))}))]
    [(find-pginstance-by-role pginstances "master")
     (find-pginstance-by-role pginstances "replica")]))
