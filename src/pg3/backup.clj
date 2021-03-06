(ns pg3.backup
  (:require [pg3.fsm :as fsm]
            [k8s.core :as k8s]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [unifn.core :as u]
            [pg3.utils :as ut]
            [clojure.string :as str]))

(defmethod u/*fn ::backup-enable? [{backup :resource}]
  (when-not (get-in backup [:spec :enabled?])
    {::u/status :stop}))

(defmethod u/*fn ::time-for-backup? [{backup :resource}]
  (let [last-updated (get-in backup [:status :last-backup :lastUpdate])
        backup-period (ut/parse-period (get-in backup [:spec :period]))
        since-last-backup (ut/since last-updated)]
    (when (or (nil? last-updated)
              (> since-last-backup backup-period))
      {::u/status :success})))

(defmethod u/*fn ::schedule-backup [{backup :resource}]
  (let [pod-spec (model/backup-pod-spec backup)
        result (k8s/create pod-spec)]
    (if (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)}
      {::u/status :success
       :status-data {:pod-spec pod-spec}})))

(defmethod u/*fn ::load-pod [{backup :resource}]
  (let [pod (k8s/find (get-in backup [:status :pod-spec]))]
    (if (= (:kind pod) "Status")
      {::u/status :error
       ::u/message (str pod)}
      {::pod pod})))

(defmethod u/*fn ::check-status [{pod ::pod}]
  (let [result (k8s/logs pod)]
    (case (get-in pod [:status :phase])
     "Succeeded" (let [now (ut/now-string)]
                   {::last-backup {:lastUpdate now
                                   :name result}})
     "Failed" {::u/status :error
               ::u/message (str "\n" result)}
     {::u/status :stop})))

(defmethod u/*fn ::delete-pod [{pod ::pod}]
  (let [result (k8s/delete pod)]
    (when (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)})))

(defmethod u/*fn ::backup-completed [{backup-spec :resource
                                      last-backup ::last-backup}]
  {::u/status :success
   ::u/message (str "Backup for " (get-in backup-spec [:spec :pg-cluster]) " was done.\n" (:name last-backup))
   :status-data {:last-backup last-backup}})

(defmethod u/*fn ::load-pg-instances [{backup :resource}]
  {::ut/pginstances (ut/pginstances (get-in backup [:metadata :namespace]) (get-in backup [:metadata :labels :service]))})

(def fsm-backup
  {:init {:action-stack [::load-pg-instances
                         {::u/fn ::ut/cluster-active?}
                         {::u/fn ::ut/success
                          ::ut/message "Backup schedule was initialized"}]
          :success :wait-for-new-backup
          :error :error-state}
   :wait-for-new-backup {:action-stack [::backup-enable?
                                        ::time-for-backup?]
                         :success :schedule-backup
                         :error :wait-for-new-backup}
   :schedule-backup {:action-stack [::schedule-backup]
                     :success :waiting-backup-finish
                     :error :schedule-backup}
   :waiting-backup-finish {:action-stack [::load-pod
                                          ::check-status
                                          ::delete-pod
                                          ::backup-completed]
                           :success :wait-for-new-backup
                           :error :wait-for-new-backup}
   :error-state {}})

(defn watch []
  (doseq [backup (:items (k8s/query {:kind naming/backup-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-backup backup )))
