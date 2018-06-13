(ns pg3.model
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [cheshire.core :as json]
            [pg3.naming :as naming]
            [k8s.core :as k8s]))

(def api-group "pg3.io")

(def cluster-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name naming/cluster-resource-name}
   :spec {:group naming/api-group
          :version naming/api-version
          :names {:kind naming/cluster-resource-kind
                  :plural naming/cluster-resource-plural}
          :scope "Namespaced"}})

(def instance-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name naming/instance-resource-name}
   :spec {:group naming/api-group
          :version naming/api-version
          :names {:kind naming/instance-resource-kind
                  :plural naming/instance-resource-plural}
          :scope "Namespaced"}})

(defn inherited-namespace [x]
  (or (get-in x [:metadata :namespace]) "default"))

(defn inherited-labels [x]
  (or (get-in x [:metadata :labels]) {}))

(defn instance-spec [cluster color role]
  {:kind naming/instance-resource-kind
   :apiVersion naming/api
   :metadata {:name (naming/instance-name cluster color)
              :namespace (inherited-namespace cluster)
              :labels (merge (naming/cluster-labels cluster)
                             (naming/instance-labels role color))}
   :spec (merge (:spec cluster)
                {:pg-cluster (naming/resource-name cluster)
                 :role role})
   :config (:config cluster)})

(def default-volume-annotiations {"volume.beta.kubernetes.io/storage-class" "standard"})

(defn volume-spec
  [{nm :name
    labels :labels
    ns :namespace
    size :storage
    anns :annotations}]
  {:kind "PersistentVolumeClaim"
   :apiVersion "v1"
   :metadata {:name nm
              :lables labels
              :namespace ns
              :annotations  (merge default-volume-annotiations anns)}
   :spec {:accessModes ["ReadWriteOnce"]
          :resources {:requests {:storage size}}}})

(defn instance-data-volume-spec [inst-spec]
  (volume-spec
   {:name (naming/data-volume-name inst-spec)
    :labels (merge (inherited-labels inst-spec) {:pgrole "data"})
    :namespace (inherited-namespace inst-spec)
    :annotations {"volume.beta.kubernetes.io/storage-class" (get-in inst-spec [:spec :storageClass])}
    :storage (get-in inst-spec [:spec :size])}))

(defn instance-wals-volume-spec [inst-spec]
  (volume-spec
   {:name (naming/wals-volume-name inst-spec)
    :labels (merge (inherited-labels inst-spec) {:pgrole "data"})
    :namespace (inherited-namespace inst-spec)
    :annotations {"volume.beta.kubernetes.io/storage-class" (get-in inst-spec [:spec :storageClass])}
    :storage (get-in inst-spec [:spec :size])}))

(def preffered-postgresql-config
  {:synchronous_commit :off
   :max_connections 100
   :shared_buffers "1GB"
   :max_replication_slots  30
   :archive_timeout "10min"
   :max_wal_size  "1GB"
   :max_wal_senders 30
   :wal_keep_segments 100})

(def default-postgresql-config
  {:listen_addresses "*"
   :synchronous_commit :off
   :wal_log_hints :on
   :port 5432
   :hot_standby :on
   :wal_level :logical
   :archive_mode :on
   :archive_command  (str "test ! -f " naming/wals-path "/%f && cp %p " naming/wals-path "/%f")})


(defn generate-config [cfg]
  (->> cfg
       (mapv (fn [[k v]]
               (str (name k) " = " (cond
                                     (string? v) (str "'" v "'")
                                     (number? v) v
                                     (keyword? v) (name v)
                                     :else v))))
       (str/join "\n")))

(defn pg-config [cluser]
  (let [cfg (or (get-in cluser [:config :config]) {})]
    (generate-config
     (merge preffered-postgresql-config
            cfg
            default-postgresql-config))))

(defn pg-hba [inst-spec]
  "
local all  all                trust
host  all  all 127.0.0.1/32   trust
host  all  all ::1/128        trust
host  all all all md5
host  replication postgres 0.0.0.0/0 md5
")


(defn init-script [cluster]
  (str/join "\n"
            ["#!/bin/bash"
             "set -e"
             "set -x"
             "export PATH=/pg/bin:$PATH"
             (format "initdb --data-checksums -E 'UTF-8' --lc-collate='en_US.UTF-8' --lc-ctype='en_US.UTF-8' -D %s" naming/data-path) 
             "echo start "
             (str "cp " naming/config-path "/postgresql.conf " naming/data-path "/postgresql.conf")
             (str "cp " naming/config-path "/pg_hba.conf " naming/data-path "/pg_hba.conf")
             (str "pg_ctl start -w -D " naming/data-path)
             "echo $PGPASSWORD"
             "echo \"ALTER USER postgres WITH SUPERUSER PASSWORD '$PGPASSWORD' \" | psql --echo-all postgres"
             "echo stop"
             (str "pg_ctl stop -w -D " naming/data-path)]))

(defn config-map [cluster]
  {:kind "ConfigMap"
   :apiVersion "v1"
   :metadata {:name (naming/config-map-name (get-in cluster [:metadata :name])) 
              :labels (inherited-labels cluster)
              :namespace (inherited-namespace cluster)}
   :data {"postgresql.conf" (pg-config cluster)
          "pg_hba.conf" (pg-hba cluster) 
          "initscript" (init-script cluster)}})

(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn secret [cluster & [pass]]
  {:kind "Secret"
   :apiVersion "v1"
   :type "Opaque"
   :metadata {:name (naming/secret-name (get-in cluster [:metadata :name]))
              :labels (inherited-labels cluster) 
              :namespace (inherited-namespace cluster)}
   :data {:username (k8s/base64-encode "postgres")
          :password (k8s/base64-encode (or pass (rand-str 10)))}})

(defn image [{spec :spec}]
  (str (:image spec) ":" (:version spec)))

(defn volumes [inst-spec]
  [(let [nm (naming/data-volume-name inst-spec)]
     {:name nm :persistentVolumeClaim {:claimName nm}})
   (let [nm (naming/wals-volume-name inst-spec)]
     {:name nm :persistentVolumeClaim {:claimName nm}})
   (let [nm (naming/config-map-name (get-in inst-spec [:spec :pg-cluster]))]
     {:name nm :configMap {:name nm}})])


(defn volume-mounts [inst-spec]
  [{:name (naming/data-volume-name inst-spec)
    :mountPath naming/data-path
    :subPath "pgdata"}
   {:name (naming/wals-volume-name inst-spec)
    :mountPath naming/wals-path
    :subPath "pgwals"}
   {:name (naming/config-map-name (get-in inst-spec [:spec :pg-cluster]))
    :mountPath naming/config-path}])

(defn initdb-command []
  ["/bin/sh"
   "-c"
   "-x"
   (str/join " && "
             [(format "chown postgres -R %s" naming/data-path)
              (format "chown postgres -R %s" naming/wals-path)
              (format "su -m -l postgres -c 'bash %s/initscript'" naming/config-path)])])

(defn db-pod [inst-spec opts]
  {:kind "Pod"
   :apiVersion "v1"
   :metadata {:name (:name opts)
              :namespace (inherited-namespace inst-spec)
              :labels (inherited-labels inst-spec)}
   :spec {:restartPolicy (or (:restartPolicy opts) "Always")
          :volumes (volumes inst-spec)
          :containers
          [{:name "pg"
            :image (image inst-spec)
            :ports [{:containerPort 5432}]
            :env
            [{:name "PGUSER" :value "postgres"}
             {:name "PGPASSWORD" :valueFrom {:secretKeyRef
                                             {:name (naming/secret-name (get-in inst-spec [:spec :pg-cluster]))
                                              :key "password"}}}]
            :command (:command opts)
            :volumeMounts (volume-mounts inst-spec)}]}})

(defn initdb-pod [inst-spec]
  (db-pod inst-spec
          {:name (str (get-in inst-spec [:metadata :name]) "-initdb")
           :restartPolicy "Never"
           :command (initdb-command)}))


(defn init-replica-command [cluster secret color]
  (let [host (naming/cluster-name cluster)
        user (k8s/base64-decode (get-in secret [:data :username]))
        password (k8s/base64-decode (get-in secret [:data :password]))]
    ["/bin/sh"
     "-c"
     "-x"
     (str/join " && "
               [(format "rm -rf %s/*" naming/data-path)
                (format "echo '%s:5432:*:%s:%s' >> ~/.pgpass" host user password)
                (format "/pg/bin/psql -h %s -U postgres -c \"SELECT pg_create_physical_replication_slot('%s');\" || echo 'already here' " host color)
                (format "/pg/bin/pg_basebackup -D %s -Fp -h %s -U %s -w -R -Xs -c fast -l %s -P -v" naming/data-path host user color)
                (format "echo \"primary_slot_name = '%s'\" >> %s/recovery.conf" color naming/data-path )
                (format "echo \"standby_mode = 'on'\" >> %s/recovery.conf" naming/data-path)
                (format "chown postgres -R %s" naming/data-path)
                (format "chown postgres -R %s" naming/wals-path)
                (format "chmod -R 0700 %s" naming/data-path)])]))

(defn init-replica-pod [cluster secret inst-spec]
  (db-pod inst-spec {:name (str (get-in inst-spec [:metadata :name]) "-init-replica")
                     :restartPolicy "Never"
                     :command (init-replica-command cluster secret (get-in inst-spec [:metadata :labels :color]))}))


;; TODO liveness https://github.com/kubernetes/kubernetes/issues/7891
(defn master-pod [inst-spec opts]
  (db-pod inst-spec
          (merge opts {:command
                       ["gosu", "postgres", "postgres",
                        (str "--config-file=" naming/config-path "/postgresql.conf")
                        (str "--hba-file=" naming/config-path "/pg_hba.conf")]})))

(defn master-deployment [inst-spec]
  (let [pod (master-pod inst-spec
                        {:name (str "pg3-" (get-in inst-spec [:spec :pg-cluster])
                                    "-" (get-in inst-spec [:metadata :labels :color]))})]
    {:apiVersion "apps/v1beta1"
     :kind "Deployment"
     :metadata (:metadata pod) 
     :spec {:replicas 1
            :template (update pod :metadata dissoc :name)}}))

(defn replica-deployment [cluster secret inst-spec]
  (let [pod (master-pod cluster secret inst-spec
                        {:name (str "pg3-" (get-in cluster [:metadata :name])
                                    "-" (get-in inst-spec [:metadata :labels :color])
                                    )})]
    {:apiVersion "apps/v1beta1"
     :kind "Deployment"
     :metadata (:metadata pod) 
     :spec {:replicas 1
            :template (update pod :metadata dissoc :name)}}))

(defn master-service [inst-spec]
  (let [cluster-name (get-in inst-spec [:spec :pg-cluster])]
    {:apiVersion "v1"
     :kind "Service"
     :metadata {:name (naming/service-name cluster-name) 
                :namespace (inherited-namespace inst-spec)
                :labels (inherited-labels inst-spec)}
     :spec {:selector (naming/master-service-selector cluster-name) 
            :type "ClusterIP"
            :ports [{:protocol "TCP"
                     :port 5432
                     :targetPort 5432}]}}))

(defn slave-service [cluster inst-spec]
  (let [clr (get-in inst-spec [:metadata :labels :color])]
    {:apiVersion "v1"
     :kind "Service"
     :metadata {:name (naming/replica-service-name inst-spec)
                :namespace (inherited-namespace inst-spec)
                :labels  (inherited-labels inst-spec)}
     :spec {:selector (naming/replica-service-selector cluster clr)
            :type "ClusterIP"
            :ports [{:protocol "TCP"
                     :port 5432
                     :targetPort 5432}]}}))
