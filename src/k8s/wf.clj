(ns k8s.wf
  (:require [unifn.core :as u]))

(defn execute [actions]
  )

(defn timeout? [ctx spec resource])

(defn tick [ctx machine resource]
  (let [phase (get-in resource [:status :phase])
        eng (:engine machine)
        spec (get-in machine [:machine (when phase (keyword phase))])]

    (if (timeout? ctx spec resource)
      {:status :Failed :reason "timeout"}
      (if-let [pre (:pre spec)]
        (let [{st :status :as res} (u/*apply (assoc ctx :resource resource))]
          (if (= :success st)
            (let [{st :status :as actions} (u/*apply (:action spec) res)]
              (if (= :success st)
                (u/*apply eng actions)
                (comment "decrease retry")))))))

    ;; check timeout
    ;; run pre
    ;; if true
    ;; run action
    ;; run effects
    ;; if false increase retry cnt

    ))
