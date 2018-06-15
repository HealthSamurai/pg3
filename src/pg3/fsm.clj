(ns pg3.fsm
  (:require [unifn.core :as u]
            [pg3.telegram :as t]
            [k8s.core :as k8s]))

(defn update-status [resource state-key status]
  (k8s/patch
   (assoc resource :status (merge (or (:status resource) {})
                                  status
                                  {:lastUpdate (java.util.Date.)
                                   :phase (name state-key)}))))

(defn process-state [fsm resource]
  (let [state-key (keyword (get-in resource [:status :phase] "init"))]
    (if-let [state (fsm state-key)]
      (let [action-stack (or (:action-stack state) [])
            result (u/*apply action-stack {:resource resource
                                           ::u/safe? true})]
        (when-let [notify (get {:error t/error :success t/success} (::u/status result))]
          (notify (name state-key) (::u/message result) resource))
        (when-let [next-state (get state (::u/status result))]
          (update-status resource next-state (:status result))))
      (do
        (t/error (name state-key) "Unprocessable state" resource)
        (update-status resource :unprocessable-state {})))))

(comment
  (def resource-atom (atom {:kind "TestResource"
                            :metadata {:name "my-resource"}}))
  (do
    (defmethod u/*fn ::first-step [arg]
      (println ::first-step arg))
    (defmethod u/*fn ::second-step [arg]
      (throw (Exception. "some text"))
      (println ::second-step arg))
    (defmethod u/*fn ::third-step [arg]
      (println ::third-step arg)
      {::u/status :error
       ::u/message "Thir step error"})
    (defmethod u/*fn ::last-step [arg]
      (println ::third-step arg)
      {::u/status :pending})
    (defmethod u/*fn ::process-error [arg]
      (println ::process-error arg)
      {::u/status :success})
    (defmethod u/*fn ::process-error-2 [arg]
      (println ::process-error-2 arg)
      {::u/status :success})

    (def sample {:init {:action-stack [::first-step
                                       ::second-step]
                        :success :next-state
                        :error :error-state}
                 :next-state {:action-stack [::third-step]
                              :success :last-state
                              :error :error-state-2}
                 :last-state {:action-stack [::last-step]
                              :error :error-state-2}

                 :error-state {:action-stack [::process-error]
                               :success :next-state}
                 :error-state-2 {:action-stack [::process-error-2]
                                 :success :last-state}})

    (with-redefs-fn {#'k8s/patch (fn [resource]
                                   (reset! resource-atom resource))}
      (fn []
        (process-state sample @resource-atom))))
  )
