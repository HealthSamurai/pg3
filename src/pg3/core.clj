(ns pg3.core
  (:require [k8s.core :as k8s]
            [unifn.core :as u]))

(def pgd {:apiVersion "apiextensions.k8s.io/v1beta1"
          :kind "CustomResourceDefinition"
          :metadata {:name "pgs.health-samurai.io"}
          :spec {:group "health-samurai.io"
                 :version "v1"
                 :scope "Namespaced"
                 :names {:kind "Pg" :plural "pgs"}}})

(def pgs  {:apiVersion "apiextensions.k8s.io/v1beta1"
           :kind "CustomResourceDefinition"
           :metadata {:name "pgstatuses.health-samurai.io"}
           :spec {:group "health-samurai.io"
                  :version "v1"
                  :scope "Namespaced"
                  :names {:kind "PgStatus" :plural "pgstatuses"}}})

(defn init []
  (doseq [crd [pgd pgs]]
    (k8s/create crd)))

(k8s/create pgs)



(def spec
  {:apiVersion "health-samurai.io/v1"
   :kind "Pg"
   :metadata {:name "cleo"
              :namespace "default"}
   :ns "default"
   :spec {:volume "1Gi"
          :replicas 1
          :backups {:frequency "daily"
                    :retention 10}
          :config {:max_connections "100"
                   :shared_buffers "2GB"
                   :effective_cache_size "6GB"
                   :work_mem "20971kB"
                   :maintenance_work_mem "512MB"
                   :min_wal_size "1GB"
                   :max_wal_size "2GB"
                   :checkpoint_completion_target "0.7"
                   :wal_buffers "16MB"
                   :default_statistics_target "100"}}})

(comment
  (k8s/create spec)

  (k8s/query {:kind "PgStatus"
              :id "cleo"
              :ns "default"
              :plural "pgstatuses"
              :apiVersion "health-samurai.io/v1"} "cleo")

  (k8s/patch {:kind "PgStatus"
              :id "cleo"
               :plural "pgstatuses"
               :apiVersion "health-samurai.io/v1"
              :status "init"
              :phase "creating-vpc"
              :history [{:tx (java.util.Date.) :status "init"}
                        {:tx (java.util.Date.) :status "creating-vpc"}]})

  (init)
)

