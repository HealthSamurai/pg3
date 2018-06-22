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
  (let [last-updated (get-in backup [:status :last-backup :lastUpdated])
        backup-period (ut/parse-period (get-in backup [:spec :period]))
        since-last-backup (ut/since last-updated)
        _ (println since-last-backup)]
    (when (or (nil? last-updated)
              (> since-last-backup backup-period))
      {::u/status :success
       ::u/message (str "Schedule backup for " (get-in backup [:spec :pg-cluster]))})))

(defmethod u/*fn ::schedule-backup [{backup :resource}]
  (let [pod-spec (model/backup-pod-spec backup)
        result (k8s/create pod-spec)]
    (if (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)}
      {::u/status :success
       ::u/message (str "Starting backuping for " (get-in backup [:spec :pg-cluster]))
       :status-data {:pod-spec pod-spec}})))

(defmethod u/*fn ::load-pod [{backup :resource}]
  (let [pod (k8s/find (get-in backup [:status :pod-spec]))]
    (if (= (:kind pod) "Status")
      {::u/status :error
       ::u/message (str pod)}
      {::pod pod})))

(defmethod u/*fn ::check-status [{pod ::pod}]
  (case (get-in pod [:status :phase])
    "Succeeded" (let [result (k8s/logs pod)
                      now (ut/now-string)]
                  {::last-backup {:lastUpdate now
                                  :name result}})
    "Failed" {::u/status :error
              ::u/message (str "\n" (str/join "\n" (-> pod
                                                       (get-in [:status :containerStatuses])
                                                       (->> (map (get-in [:state :terminated :message]))))))}
    {::u/status :stop}))

(defmethod u/*fn ::delete-pod [{backup-spec :resource
                                last-backup ::last-backup
                                pod ::pod}]
  (let [result {}#_(k8s/delete pod)]
    (if (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)}
      {::u/status :success
       ::u/message (str "Backup for " (get-in backup-spec [:spec :pg-cluster]) "done.\n" (:name last-backup))
       :status-data {:last-backup last-backup}})))

(defmethod u/*fn ::load-pg-instances [{backup :resource}]
  (println "!!!!!!!!!!"
           (get-in backup [:metadata :namespace]) (get-in backup [:metadata :labels :service])
           (ut/pginstances (get-in backup [:metadata :namespace]) (get-in backup [:metadata :labels :service]))
           )
  {::ut/pginstances (ut/pginstances (get-in backup [:metadata :namespace]) (get-in backup [:metadata :labels :service]))})

(def fsm-backup
  {:init {:action-stack [::load-pg-instances
                         {::u/fn ::ut/cluster-active?
                          ::ut/success-message "Starting backup loop"}]
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
                                          ::delete-pod]
                           :success :wait-for-new-backup
                           :error :wait-for-new-backup}
   :error-state {}})

(defn watch []
  (doseq [backup (:items (k8s/query {:kind naming/backup-resource-kind
                                      :apiVersion naming/api}))]
    (println backup)
    (fsm/process-state fsm-backup backup )))
