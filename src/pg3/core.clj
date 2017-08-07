(ns pg3.core)

{:name "aidboxdb"
 :image "aidboxdb"
 :version "latest"
 :config {:max_connections 100 :log_level :logical}
 :replicas {:slave {:type :sync}}
 :backups {:every "day" :at "20:00" :keep 10
           :storage {:type "google"}}
 :keep-alive {:sql "select 1"}
 :volume {:size "100G"}
 :notifications {:telegram {:config "..."}}}
