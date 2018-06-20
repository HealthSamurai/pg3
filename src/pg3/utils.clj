(ns pg3.utils
  (:require [k8s.core :as k8s]
            [pg3.naming :as naming]
            [clojure.string :as str]))

(defn find-pginstance-by-role [instances role]
  (first (filter #(= role (get-in % [:spec :role])) instances)))

(defn my-pginstances [cluster]
  (let [pginstances (:items (k8s/query {:kind naming/instance-resource-kind
                                        :ns (get-in cluster [:metadata :namespace])
                                        :apiVersion naming/api}
                                       {:labelSelector
                                        (format "service in (%s)" (naming/cluster-name cluster))}))]
    {:master (find-pginstance-by-role pginstances "master")
     :replica (find-pginstance-by-role pginstances "replica")}))

(defn read-int [s]
  (Integer/parseInt (str/trim s)))

(defn resource-ok? [resource]
  (every? (partial = "True") (map :status (get-in resource [:status :conditions]))))

(defn resource-errors [resource]
  (->> resource
       ((comp :conditions :status))
       (filter (fn [c] (= (:status c) "False")))
       (mapv :message)))
