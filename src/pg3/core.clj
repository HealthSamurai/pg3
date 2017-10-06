(ns pg3.core
  (:require [k8s.core :as k8s]
            [unifn.core :as u]))

(defn init []
  (doseq [crd [{:apiVersion "apiextensions.k8s.io/v1beta1"
                :kind "CustomResourceDefinition"
                :metadata {:name "pgs.health-samurai.io"}
                :spec {:group "health-samurai.io"
                       :version "v1"
                       :scope "Namespaced"
                       :names {:kind "Pg" :plural "pgs"}}}
               {:apiVersion "apiextensions.k8s.io/v1beta1"
                :kind "CustomResourceDefinition"
                :metadata {:name "pgss.health-samurai.io"}
                :spec {:group "health-samurai.io"
                       :version "v1"
                       :scope "Namespaced"
                       :names {:kind "PgStatus" :plural "pgstatuses"}}}]]
    (k8s/create crd)))

#_(k8s/query {:kind "CustomResourceDefinition"
            :apiVersion "apiextensions.k8s.io/v1beta1"})


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

  (init)

  )

