(ns old)


(ns pg3.core
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:gen-class))

(def data-path "/data")
(def wals-path "/wals")
(def config-path "/config")

(defn debug [x]
  (spit "/tmp/result.yaml"
        (yaml/generate-string x :dumper-options {:flow-style :block})))

;; we need to create table for custom resource
(def cluster-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name "pgs.pg3.io"}
   :spec {:group "pg3.io"
          :version "v1"
          :names {:kind "Pg", :plural "pgs"}
          :scope "Namespaced"}})

(def instance-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name "pginstances.pg3.io"}
   :spec {:group "pg3.io"
          :version "v1"
          :names {:kind "PgInstance", :plural "pginstances"}
          :scope "Namespaced"}})

(def config-definition
  {:apiVersion "apiextensions.k8s.io/v1beta1"
   :kind "CustomResourceDefinition"
   :metadata {:name "pgconfigs.pg3.io"}
   :spec {:group "pg3.io"
          :version "v1"
          :names {:kind "PgConfig", :plural "pgconfig"}
          :scope "Namespaced"}})

(defn init []
  (k8s/create cluster-definition)
  (k8s/create instance-definition)
  (k8s/create config-definition))


(defn cluster-name [cluster]
  (str "pg3-" (get-in cluster [:metadata :name])))

(defn instance-spec [cluster color role]
  {:kind "PgInstance"
   :ns "default"
   :apiVersion "pg3.io/v1"
   :metadata {:name (str (get-in cluster [:metadata :name]) "-" color)
              :namespace (or (get-in cluster [:metadata :namespace])
                             "default")
              :labels (assoc (get-in cluster [:metadata :labels])
                             :system "pg3"
                             :service (cluster-name cluster)
                             :color color
                             :pgrole role)}
   :spec (merge (:spec cluster)
                {:pg (get-in cluster [:metadata :name])
                 :role role})
   :config (:config cluster)})

(defn data-volume-name [inst-spec]
  (str (get-in inst-spec [:metadata :name]) "-data"))

(defn instance-data-volume-spec [inst-spec]
  {:kind "PersistentVolumeClaim"
   :apiVersion "v1"
   :metadata {:name (data-volume-name inst-spec) 
              :lables (assoc (get-in inst-spec [:metadata :labels])
                             :system "pg3"
                             :pgrole "data")
              :namespace (or (get-in inst-spec [:metadata :namespace])
                             "default")
              :annotations {"volume.beta.kubernetes.io/storage-class" "standard"}}
   :spec {:accessModes ["ReadWriteOnce"]
          :resources {:requests {:storage (get-in inst-spec [:spec :size])}}}})

(defn wals-volume-name [inst-spec]
  (str (get-in inst-spec [:metadata :name]) "-wals"))

(defn instance-wals-volume-spec [inst-spec]
  {:kind "PersistentVolumeClaim"
   :apiVersion "v1"
   :metadata {:name (wals-volume-name inst-spec)
              :lables (assoc (get-in inst-spec [:metadata :labels])
                             :pgrole "wals"
                             :system "pg3")
              :namespace (or (get-in inst-spec [:metadata :namespace])
                             "default")
              :annotations {"volume.beta.kubernetes.io/storage-class" "standard"}}
   :spec {:accessModes ["ReadWriteOnce"]
          :resources {:requests {:storage (get-in inst-spec [:spec :size])}}}})


(def default-postgresql-config
  {:listen_addresses "*"
   :shared_preload_libraries "pg_pathman"
   :synchronous_commit :off
   :max_connections 100
   :shared_buffers "1GB"
   :wal_log_hints :on
   :port 5432
   :hot_standby :on
   :wal_level :logical
   :max_replication_slots  30
   :archive_mode :on
   :archive_command  (str "test ! -f " wals-path "/%f && cp %p " wals-path "/%f")
   :archive_timeout "10min"
   :max_wal_size  "1GB"
   :max_wal_senders 30
   :wal_keep_segments 100})

(defn generate-config [cfg]
  (->> cfg
       (mapv (fn [[k v]]
               (str (name k) " = " (cond
                                     (string? v) (str "'" v "'")
                                     (number? v) v
                                     (keyword? v) (name v)
                                     :else v))))
       (str/join "\n")))

(comment
  (generate-config default-postgresql-config))

(defn pg-config [inst-spec]
  (let [cfg (or {} (get-in inst-spec [:config :config]))]
    (generate-config (merge cfg default-postgresql-config))))

(defn pg-hba [inst-spec]
  "
local all  all                trust
host  all  all 127.0.0.1/32   trust
host  all  all ::1/128        trust
host  all all all md5
host  replication postgres 0.0.0.0/0 md5
")

(defn config-map-name [cluster]
  (str "pg3-" (get-in cluster [:metadata :name])))

(defn config-map [cluster]
  {:kind "ConfigMap"
   :apiVersion "v1"
   :metadata {:name (config-map-name cluster) 
              :labels {:system "pg3"}
              :namespace (or (get-in cluster [:metadata :namespace]) "default")}
   :data {"postgresql.conf" (pg-config cluster)
          "pg_hba.conf" (pg-hba cluster) 
          ;; TODO generate it 
          ".pgpass" "db:5432:*:postgres:secret"
          "initscript"
          (str/join "\n"
                    ["#!/bin/bash"
                     "set -e"
                     "set -x"
                     "export PATH=/pg/bin:$PATH"
                     (str "initdb --data-checksums -E 'UTF-8' --lc-collate='en_US.UTF-8' --lc-ctype='en_US.UTF-8' -D " data-path) 
                     "echo start "
                     (str "cp " config-path "/postgresql.conf " data-path "/postgresql.conf")
                     (str "cp " config-path "/pg_hba.conf " data-path "/pg_hba.conf")
                     (str "pg_ctl start -w -D " data-path)
                     "echo $PGPASSWORD"
                     "echo \"ALTER USER postgres WITH SUPERUSER PASSWORD '$PGPASSWORD' \" | psql --echo-all postgres"
                     "echo stop"
                     (str "pg_ctl stop -w -D " data-path)])}})

(defn secret-name [cluster]
  (str "pg3-" (get-in cluster [:metadata :name])))

(defn secret [cluster]
  {:kind "Secret"
   :apiVersion "v1"
   :type "Opaque"
   :metadata {:name secret-name
              :labels {:system "pg3"}
              :namespace (or (get-in cluster [:metadata :namespace]) "default")}
   :data {:username (k8s/base64-encode "postgres")
          ;; TODO generate password
          :password (k8s/base64-encode "secret")}})

(defn create-volumes [inst-spec]
  [(k8s/patch (instance-data-volume-spec inst-spec))
   (k8s/patch (instance-wals-volume-spec inst-spec))])

(defn image [{spec :spec}]
  (str (:image spec) ":" (:version spec)))

(defn volumes [cluster inst-spec]
  [{:name (data-volume-name inst-spec)
    :persistentVolumeClaim {:claimName (data-volume-name inst-spec)}}
   {:name (wals-volume-name inst-spec)
    :persistentVolumeClaim {:claimName (wals-volume-name inst-spec)}}
   {:name (config-map-name cluster)
    :configMap {:name (config-map-name cluster)}}])


(defn volume-mounts [cluster inst-spec]
  [{:name (data-volume-name inst-spec)
    :mountPath data-path
    :subPath "pgdata"}
   {:name (wals-volume-name inst-spec)
    :mountPath wals-path
    :subPath "pgwals"}
   {:name (config-map-name cluster)
    :mountPath config-path}])

(defn initdb-command [secret]
  ["/bin/sh"
   "-c"
   "-x"
   (str
    " && chown postgres -R " data-path
    " && chown postgres -R " wals-path
    " &&  su -m -l postgres -c 'bash " config-path "/initscript'")])

(defn db-pod [cluster secret inst-spec opts]
  {:kind "Pod"
   :apiVersion "v1"
   :metadata {:name (:name opts)
              :namespace (or (get-in inst-spec [:metadata :namespace]) "default")
              :labels (assoc (get-in inst-spec [:metadata :labels])
                             :service (cluster-name cluster) 
                             :system "pg3")}
   :spec {:restartPolicy (or (:restartPolicy opts) "Always")
          :volumes (volumes cluster inst-spec)
          :containers
          [{:name "pg"
            :image (image inst-spec)
            :ports [{:containerPort 5432}]
            :env
            [{:name "PGUSER" :value "postgres"}
             {:name "PGPASSWORD" :valueFrom {:secretKeyRef {:name (secret-name cluster)
                                                            :key "password"}}}]
            :command (:command opts)
            :volumeMounts (volume-mounts cluster inst-spec)}]}})

(defn initdb-pod [cluster secret inst-spec]
  (db-pod cluster secret inst-spec {:name (str (get-in inst-spec [:metadata :name]) "-initdb")
                                    :restartPolicy "Never"
                                    :command (initdb-command secret)}))


(defn init-replica-command [cluster secret color]
  (let [host (cluster-name cluster)
        user (k8s/base64-decode (get-in secret [:data :username]))
        password (k8s/base64-decode (get-in secret [:data :password]))]
    ["/bin/sh"
     "-c"
     "-x"
     (str/join " && "
               [(format "rm -rf %s/*" data-path)
                (format "echo '%s:5432:*:%s:%s' >> ~/.pgpass" host user password)
                (format "/pg/bin/psql -h %s -U postgres -c \"SELECT pg_create_physical_replication_slot('%s');\" || echo 'already here' " host color)
                (format "/pg/bin/pg_basebackup -D %s -Fp -h %s -U %s -w -R -Xs -c fast -l %s -P -v" data-path host user color)
                (format "echo \"primary_slot_name = '%s'\" >> %s/recovery.conf" color data-path )
                (format "echo \"standby_mode = 'on'\" >> %s/recovery.conf" data-path)
                (format "chown postgres -R %s" data-path)
                (format "chown postgres -R %s" wals-path)
                (format "chmod -R 0700 %s" data-path)])]))

(defn init-replica-pod [cluster secret inst-spec]
  (db-pod cluster secret inst-spec {:name (str (get-in inst-spec [:metadata :name]) "-init-replica")
                                    :restartPolicy "Never"
                                    :command (init-replica-command cluster secret (get-in inst-spec [:metadata :labels :color]))}))


;; TODO liveness https://github.com/kubernetes/kubernetes/issues/7891
(defn master-pod [cluster secret inst-spec opts]
  (db-pod cluster secret inst-spec
          (merge opts {:command
                       ["gosu", "postgres", "postgres",
                        (str "--config-file=" config-path "/postgresql.conf")
                        (str "--hba-file=" config-path "/pg_hba.conf")]})))

(defn master-deployment [cluster secret inst-spec]
  (let [pod (master-pod cluster secret inst-spec
                        {:name (str "pg3-" (get-in cluster [:metadata :name])
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

(defn master-service [cluster inst-spec]
  {:apiVersion "v1"
   :kind "Service"
   :metadata {:name (cluster-name cluster) 
              :namespace (or (get-in inst-spec [:metadata :namespace]) "default")
              :labels {:system "pg3"}}
   :spec {:selector {:service (cluster-name cluster) 
                     :pgrole "master"}
          :type "ClusterIP"
          :ports [{:protocol "TCP"
                   :port 5432
                   :targetPort 5432}]}})

(defn slave-service [cluster inst-spec]
  (let [clr (get-in inst-spec [:metadata :labels :color])
        c-name (cluster-name cluster)]
    {:apiVersion "v1"
     :kind "Service"
     :metadata {:name (str c-name "-" clr)
                :namespace (or (get-in inst-spec [:metadata :namespace]) "default")
                :labels {:system "pg3"
                         :color clr
                         :service c-name}}
     :spec {:selector {:service c-name
                       :color clr
                       :pgrole "replica"}
            :type "ClusterIP"
            :ports [{:protocol "TCP"
                     :port 5432
                     :targetPort 5432}]}}))

(defn instance-status [inst-spec]

  )

(defn secrets-map []
  (let [secrets (:items (k8s/query {:kind "Secret" :apiVersion "v1"}
                                   {:labelSelector "system in (pg3)"}))]

    (->> 
     secrets
     (reduce (fn [acc {m :metadata s :data}]
               (assoc acc (:name m)
                      (reduce (fn [acc [k v]]
                                (assoc acc k (k8s/base64-decode v))
                                ) {} s))) {}))))


(defn watch-clusters []
  (doseq [cluster (:items (k8s/query {:kind "Pg" :apiVersion "pg3.io/v1"}))]
    (println "Cluster: " cluster)))

(defn watch-instances []
  (let [secrets (:items (k8s/query {:kind "Secret" :apiVersion "v1"}
                                   {:labelSelector "system in (pg3)"}))] 

    (doseq [i (:items (k8s/query {:kind "PgInstance"
                                  :ns "default"
                                  :apiVersion "pg3.io/v1"}))]
      (let [status (instance-status i)]
        (println
         (get-in i [:metadata :name])
         " - "
         (get-in i [:metadata :labels :color])
         "("
         (get-in i [:metadata :labels :pgrole])
         ")"
         " status: "
         status)))))

(defn watch []
  (watch-instances)
  (watch-clusters)
  )

(comment
  (watch)

  (secrets-map)
  )


(defonce server (atom nil))
(defn stop []
  (when-let [thr @server]
    (.interrupt thr)
    (reset! server nil)))

(defn start []
  (stop)
  (let [thr (Thread.
             (fn []
               (println "Start")
               (try
                 (while (not (Thread/interrupted))
                   (watch)
                   (Thread/sleep 10000))
                 (catch java.lang.InterruptedException e
                   (println "Bay, bay")))))]
    (reset! server thr)
    (.start thr)))

(defn -main []
  (start))

(comment

  (start)
  (stop)

  (->
   (init)
   debug)

  (-> (k8s/query cluster-definition)
      (debug))

  (def test-db
    {:kind "Pg"
     :ns "default"
     :apiVersion "pg3.io/v1"
     :metadata {:name "cleo-prod"
                :labels {:service "cleo"
                         :system "pg3"
                         :stage "prod"}}
     :spec {:image "aidbox/aidboxdb"
            :version "passive"
            :size "300Gi"
            :replicas {:sync 1}}
     :config {:config {:shared_buffers "1GB"
                       :max_connections 100}}})

  (-> (k8s/patch test-db)
      (debug))

  (-> (config-map test-db)
      (k8s/patch)
      (debug))

  (def db-secret (secret test-db))

  db-secret

  (-> db-secret
      (k8s/patch)
      (debug))

  (def test-inst-1
    (->
     test-db
     (instance-spec "green" "master")))

  test-inst-1

  (->
   test-inst-1
   (k8s/patch)
   (debug))

  test-inst-1

  (instance-data-volume-spec test-inst-1)

  (instance-data-volume-spec test-inst-1)

  (k8s/patch (instance-data-volume-spec test-inst-1))

  (count (:items (k8s/query (instance-data-volume-spec test-inst-1))))
  

  (create-volumes test-inst-1)

  (-> (k8s/curl "/api/v1")
      (json/parse-string)
      (debug))

  (k8s/delete
   (initdb-pod test-db db-secret test-inst-1))

  (->
   (initdb-pod test-db db-secret test-inst-1)
   ;; (json/generate-string {:pretty true})
   (k8s/create)
   ;; (->> (spit "/tmp/result.yaml"))
   (debug)
   )


  (-> (master-deployment test-db db-secret test-inst-1)
      (k8s/patch)
      (debug))

  (-> (master-service test-db test-inst-1)
      (k8s/patch)
      (debug))


  (def inst-slave
    (->
     test-db
     (instance-spec "blue" "replica")))

  inst-slave

  (k8s/create inst-slave)

  (create-volumes inst-slave)


  (->
   (init-replica-pod test-db db-secret inst-slave)
   (k8s/create)
   (debug))

  #_(k8s/delete
   (init-replica-pod test-db db-secret inst-slave))

  (-> (replica-deployment test-db db-secret inst-slave)
      (k8s/patch)
      (debug))

  (-> (slave-service test-db inst-slave)
      (k8s/patch)
      (debug)
      )

  )



