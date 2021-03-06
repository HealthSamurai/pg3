(ns pg3.model
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [cheshire.core :as json]
            [pg3.naming :as naming]
            [k8s.core :as k8s]
            [unifn.core :as u]))

(def api-group "pg3.io")

(def cluster-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name naming/cluster-resource-name}
   :spec {:group naming/api-group
          :version naming/api-version
          :names {:kind naming/cluster-resource-kind
                  :plural naming/cluster-resource-plural
                  :shortNames ["pgc"]}
          :scope "Namespaced"}})

(def instance-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name naming/instance-resource-name}
   :spec {:group naming/api-group
          :version naming/api-version
          :names {:kind naming/instance-resource-kind
                  :plural naming/instance-resource-plural
                  :shortNames ["pgi"]}
          :scope "Namespaced"}})

(def backup-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name naming/backup-resource-name}
   :spec {:group naming/api-group
          :version naming/api-version
          :names {:kind naming/backup-resource-kind
                  :plural naming/backup-resource-plural
                  :shortNames ["pgb"]}
          :scope "Namespaced"}})

(defn inherited-namespace [x]
  (or (get-in x [:metadata :namespace]) "default"))

(defn inherited-labels [x]
  (or (get-in x [:metadata :labels]) {}))

(defn owner-references [resource]
  [{:apiVersion (:apiVersion resource)
    :kind (:kind resource)
    :name (get-in resource [:metadata :name])
    :uid (get-in resource [:metadata :uid])
    :blockOwnerDeletion true}])

(defn replication-spec [role color slots]
  (if (= role "master")
    {:slots slots}
    {:upstream {:slot color}}))

(defn instance-spec
  ([cluster color role]
    (instance-spec cluster color role {}))
  ([cluster color role opts]
  {:kind naming/instance-resource-kind
   :apiVersion naming/api
   :metadata {:name (naming/instance-name cluster color)
              :namespace (inherited-namespace cluster)
              :ownerReferences (owner-references cluster)
              :labels (merge (naming/cluster-labels cluster)
                             (naming/instance-labels role color))}
   :spec (merge (:spec cluster)
                {:pg-cluster (naming/resource-name cluster)
                 :replication (replication-spec role color (:slots opts))
                 :role role}
                {:monitoring (get-in cluster [:spec :monitoring])})
   :config (:config cluster)}))

(defn full-backup-spec [cluster spec]
  (when spec
    {:period (get spec :period "1d")
     :pod-spec
     {:apiVersion "v1"
      :kind "Pod"
      :metadata {:namespace (inherited-namespace cluster)
                 :ownerReferences (owner-references cluster)
                 :labels (naming/cluster-labels cluster)}
      :spec {:containers [(merge
                           {:image "healthsamurai/backup-pg3:latest"}
                           (:pod-spec spec)
                           {:name "backup"
                            :env
                            [{:name "PG_USER" :value "postgres"}
                             {:name "PG_HOST" :value (naming/service-name (naming/resource-name cluster))}
                             {:name "PG_PORT" :value "5432"}
                             {:name "PGPASSWORD" :valueFrom {:secretKeyRef
                                                             {:name (naming/secret-name
                                                                     (naming/resource-name cluster))
                                                              :key "password"}}}]})]
             :restartPolicy "Never"}}}))

(defn backup-spec [cluster backup]
  {:kind naming/backup-resource-kind
   :apiVersion naming/api
   :metadata {:name (naming/backup-name cluster backup)
              :namespace (inherited-namespace cluster)
              :ownerReferences (owner-references cluster)
              :labels (naming/cluster-labels cluster)}
   :spec (merge (full-backup-spec cluster backup)
                {:pg-cluster (naming/resource-name cluster)
                 :enabled? true})})

(defn backup-pod-spec [backup]
  (-> backup
      (get-in [:spec :pod-spec])
      (assoc-in [:metadata :name] (naming/backup-pod-name backup))))

(def default-volume-annotiations {"volume.beta.kubernetes.io/storage-class" "standard"})

(defn volume-spec
  [{nm :name
    labels :labels
    owner-refs :ownerReferences
    ns :namespace
    size :storage
    anns :annotations}]
  {:kind "PersistentVolumeClaim"
   :apiVersion "v1"
   :metadata {:name nm
              :lables labels
              :namespace ns
              :ownerReferences owner-refs
              :annotations  (merge default-volume-annotiations anns)}
   :spec {:accessModes ["ReadWriteOnce"]
          :resources {:requests {:storage size}}}})

(defn instance-data-volume-spec [inst-spec]
  (volume-spec
   {:name (naming/data-volume-name inst-spec)
    :ownerReferences (owner-references inst-spec)
    :labels (merge (inherited-labels inst-spec) {:type "data"})
    :namespace (inherited-namespace inst-spec)
    :annotations {"volume.beta.kubernetes.io/storage-class" (get-in inst-spec [:spec :storageClass] "standard")}
    :storage (get-in inst-spec [:spec :size])}))

(defn instance-wals-volume-spec [inst-spec]
  (volume-spec
   {:name (naming/wals-volume-name inst-spec)
    :labels (merge (inherited-labels inst-spec) {:type "wal"})
    :ownerReferences (owner-references inst-spec)
    :namespace (inherited-namespace inst-spec)
    :annotations {"volume.beta.kubernetes.io/storage-class" (get-in inst-spec [:spec :storageClass] "standard")}
    :storage (get-in inst-spec [:spec :size])}))

(def preffered-postgresql-config
  {:synchronous_commit :remote_write
   :max_connections 100
   :shared_buffers "1GB"
   :max_replication_slots  30
   :archive_timeout 0 #_"10min"
   :max_wal_size "1GB"
   :max_wal_senders 30
   :wal_keep_segments 5 #_100})

(def default-postgresql-config
  {:listen_addresses "*"
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

(defn pg-config [cluster]
  (let [cfg (or (get-in cluster [:config :config]) {})
        sync-replicas (get-in cluster [:spec :replicas :sync] 0)
        synchronous_standby_names (if (> sync-replicas 0) {:synchronous_standby_names (format "ANY %s (*)" sync-replicas)})]
    (generate-config
     (merge preffered-postgresql-config
            cfg
            default-postgresql-config
            synchronous_standby_names))))

(defn pg-hba [inst-spec]
  "
local all  all                trust
host  all  all 127.0.0.1/32   trust
host  all  all ::1/128        trust
host  all  all all            md5
host  replication postgres 0.0.0.0/0 md5
")


(defn init-script [cluster]
  (str/join "\n"
            ["#!/bin/bash"
             "set -e"
             "set -x"
             (format "initdb --data-checksums -E 'UTF-8' --lc-collate='en_US.UTF-8' --lc-ctype='en_US.UTF-8' -D %s" naming/data-path)
             "echo start "
             (str "pg_ctl start -w -D " naming/data-path)
             "echo $PGPASSWORD"
             "echo \"ALTER USER postgres WITH SUPERUSER PASSWORD '$PGPASSWORD' \" | psql --echo-all postgres"
             "echo stop"
             (str "pg_ctl stop -w -D " naming/data-path)
             (str "cp " naming/config-path "/postgresql.conf " naming/data-path "/postgresql.conf")
             (str "cp " naming/config-path "/pg_hba.conf " naming/data-path "/pg_hba.conf")]))

(defn ensure-replication-slots []
  "#!/bin/sh

echo ensure replication slots

CURRENT_SLOTS=`psql -U postgres -qtAX -c 'select slot_name from pg_replication_slots;'`
DESIRED_SLOTS=$@

echo CURRENT_SLOTS=$CURRENT_SLOTS
echo DESIRED_SLOTS=$DESIRED_SLOTS

for current_slot in $CURRENT_SLOTS
do
  if [[ -z $(echo $DESIRED_SLOTS | grep $current_slot) ]]
  then
    psql -U postgres -c \"select pg_drop_replication_slot('$current_slot');\"
  fi
done

for desired_slot in $DESIRED_SLOTS
do
  if [[ -z $(echo $CURRENT_SLOTS | grep $desired_slot) ]]
  then
    psql -U postgres -c \"select pg_create_physical_replication_slot('$desired_slot');\"
  fi
done
")

(defn ensure-config []
  "#!/bin/sh
if ! `cmp -s /config/postgresql.conf /data/postgresql.conf`
then
  echo postgresql.conf updated
  cp /config/postgresql.conf /data/postgresql.conf
  su -m postgres -c 'pg_ctl reload'
fi
")

(defn config-map [cluster]
  {:kind "ConfigMap"
   :apiVersion "v1"
   :metadata {:name (naming/config-map-name (get-in cluster [:metadata :name]))
              :labels (inherited-labels cluster)
              :ownerReferences (owner-references cluster)
              :namespace (inherited-namespace cluster)}
   :data {"postgresql.conf" (pg-config cluster)
          "pg_hba.conf" (pg-hba cluster)
          "initscript" (init-script cluster)
          "ensure-config.sh" (ensure-config)
          "ensure-replication-slots.sh" (ensure-replication-slots)}})

(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn secret [cluster & [pass]]
  {:kind "Secret"
   :apiVersion "v1"
   :type "Opaque"
   :metadata {:name (naming/secret-name (get-in cluster [:metadata :name]))
              :labels (inherited-labels cluster)
              :ownerReferences (owner-references cluster)
              :namespace (inherited-namespace cluster)}
   :data {:username (k8s/base64-encode "postgres")
          :password (k8s/base64-encode (or pass (rand-str 10)))}})

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
              (format "su -m postgres -c 'bash %s/initscript'" naming/config-path)])])

(defn db-pod [inst-spec opts]
  {:kind "Pod"
   :apiVersion "v1"
   :metadata {:name (:name opts)
              :namespace (inherited-namespace inst-spec)
              :ownerReferences (owner-references inst-spec)
              :labels (inherited-labels inst-spec)}
   :spec {:restartPolicy (or (:restartPolicy opts) "Always")
          :volumes (volumes inst-spec)
          :containers
          [{:name "pg"
            :image (get-in inst-spec [:spec :image])
            :ports [{:containerPort 5432}]
            :imagePullPolicy :Always
            :env
            [{:name "PGUSER" :value "postgres"}
             {:name "PGPASSWORD" :valueFrom {:secretKeyRef
                                             {:name (naming/secret-name (get-in inst-spec [:spec :pg-cluster]))
                                              :key "password"}}}]
            :command (:command opts)
            :volumeMounts (volume-mounts inst-spec)
            :readinessProbe {:exec {:command ["psql" "-c" "select 1;" "-U" "postgres"]}
                             :initialDelaySeconds 5
                             :periodSeconds 5}}]}})

(defn init-master-pod [inst-spec]
  (db-pod
   (assoc-in inst-spec [:metadata :labels :type] "init")
   {:name (str (get-in inst-spec [:metadata :name]) "-init-master")
    :restartPolicy "Never"
    :command (initdb-command)}))


(defn init-replica-command [host color slot-name]
  ["/bin/sh"
   "-c"
   "-x"
   (str/join " && "
             [(format "rm -rf %s/*" naming/data-path)
              (format "echo '%s:5432:*:$PGUSER:$PGPASSWORD' >> ~/.pgpass" host)
              ; (format "psql -h %s -U postgres -c \"SELECT pg_create_physical_replication_slot('%s');\" || echo 'already here' " host color)
              (format "pg_basebackup -D %s -Fp -h %s -U $PGUSER -w -R -Xs -c fast -l %s -P -v" naming/data-path host color)
              (format "echo \"primary_slot_name = '%s'\" >> %s/recovery.conf" slot-name naming/data-path)
              (format "echo \"standby_mode = 'on'\" >> %s/recovery.conf" naming/data-path)
              (format "chown postgres -R %s" naming/data-path)
              (format "chown postgres -R %s" naming/wals-path)
              (format "chmod -R 0700 %s" naming/data-path)])])

(defn init-replica-pod [inst-spec]
  (let [host (str "pg3-" (get-in inst-spec [:spec :pg-cluster]))
        slot-name (get-in inst-spec [:spec :replication :upstream :slot])]
    (db-pod
     (assoc-in inst-spec [:metadata :labels :type] "init")
     {:name (str (get-in inst-spec [:metadata :name]) "-init-replica")
      :restartPolicy "Never"
      :command (init-replica-command host (get-in inst-spec [:metadata :labels :color]) slot-name)})))


;; TODO liveness https://github.com/kubernetes/kubernetes/issues/7891
(defn postgres-pod [inst-spec opts]
  (db-pod
   (assoc-in inst-spec [:metadata :labels :type] "instance")
   (merge opts {:command ["su" "-m" "postgres" "-c" "postgres"]})))

(defn with-monitoring? [inst-spec]
  (get-in inst-spec [:spec :monitoring]))

(defn monitoring-container [inst-spec]
  {:name "monitoring-agent"
   :image (get-in inst-spec [:spec :monitoring :image])
   :volumeMounts (volume-mounts inst-spec)
   :imagePullPolicy :Always})

(defn postgres-deployment [inst-spec]
  (let [pod (cond-> (postgres-pod inst-spec
                                  {:name (str "pg3-" (get-in inst-spec [:spec :pg-cluster])
                                              "-" (get-in inst-spec [:metadata :labels :color]))})
              true (update-in [:spec :containers]
                              conj (merge
                                    {:image "healthsamurai/wal-export:latest"}
                                    (get-in inst-spec [:spec :wal-export])
                                    {:name "pg-wal-export"
                                     :imagePullPolicy :Always
                                     :env [{:name "WAL_DIR" :value naming/wals-path}]
                                     :volumeMounts (volume-mounts inst-spec)}))
              (with-monitoring? inst-spec) (update-in [:spec :containers]
                                                      conj
                                                      (monitoring-container inst-spec)))]
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
                :ownerReferences (owner-references inst-spec)
                :labels (inherited-labels inst-spec)}
     :spec {:selector (naming/master-service-selector cluster-name) 
            :type "ClusterIP"
            :ports [{:protocol "TCP"
                     :port 5432
                     :targetPort 5432}]}}))

(defn replica-service [inst-spec]
  (let [clr (get-in inst-spec [:metadata :labels :color])
        cluster-name (get-in inst-spec [:spec :pg-cluster])]
    {:apiVersion "v1"
     :kind "Service"
     :metadata {:name (naming/replica-service-name inst-spec)
                :namespace (inherited-namespace inst-spec)
                :ownerReferences (owner-references inst-spec)
                :labels  (inherited-labels inst-spec)}
     :spec {:selector (naming/replica-service-selector cluster-name clr)
            :type "ClusterIP"
            :ports [{:protocol "TCP"
                     :port 5432
                     :targetPort 5432}]}}))
