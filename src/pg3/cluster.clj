(ns pg3.cluster
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]))

;; watch new clusters 
;; orchestarate setup

;; watch status of clusters
;; based on instances state

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


(defn init-cluster [cluster]
  (println "INIT cluster: " cluster)
  (let [[old-master old-replica] (my-pginstances cluster)
        colors (take 2 (shuffle naming/colors))
        master (or old-master (model/instance-spec cluster (first colors) "master"))
        replica (or old-replica (model/instance-spec cluster (second colors) "replica"))]


    (println "Create config: "  (k8s/patch (model/config-map cluster)))
    (println "Create secret: "  (k8s/patch (model/secret cluster)))
    (println "Create master: "  (k8s/patch master))
    (println "Create replica: " (k8s/patch replica))

    (println "Update cluster state: "
             (k8s/patch (assoc cluster
                               :kind naming/cluster-resource-kind
                               :apiVersion naming/api
                               :status {:phase "init"
                                        :ts (java.util.Date.)})))))

(defn inited-cluster? [cluster]
  (println "INITITED cluster: " cluster))

(defn cluster-status [cluster]
  (println "Status cluster: [" (:status cluster) "]" cluster)

  (let [instances (my-pginstances cluster)]
    (k8s/patch (assoc cluster
                      :kind naming/cluster-resource-kind
                      :apiVersion naming/api
                      :status
                      (merge (or (:status cluster) {})
                             {:lastUpdate (java.util.Date.)
                              :instances (reduce
                                          (fn [acc i]
                                            (assoc acc (naming/resource-name i) (:status i)))
                                          {} instances)})))))

(defn watch-cluster [{status :status :as cluster}]
  (cond
    (or (nil? status)
        (= (:phase status) "reinit")) (init-cluster cluster)
    (= "init" (:phase status)) (inited-cluster? cluster)
    :else (cluster-status cluster)))

(defn watch-clusters []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (watch-cluster cluster)))

(comment
  (watch-clusters)
  )
