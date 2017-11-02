(ns k8s.wf-test
  (:require [k8s.wf :as sut]
            [unifn.core :as u]
            [clojure.test :refer :all]))

(defmethod u/*fn ::engine
  [{actions :actions}]
  (println actions)
  {:status :success})

(def machine
  {:engine ::mock-engine
   :machine {:nil {:timeout [100 :ms] 
                   :retry 3
                   :action :CreateVolume}

             :CreateVolume {:timeout [15 :ms]
                            :retry 3
                            :pre ::pvc-created?
                            :action ::InitDB}

             :InitDB  {:retry 3
                       :pre ::db-initialized?
                       :tiemout [50 :ms]
                       :action ::Deployment}

             :Master {:pre ::connection-active?}}})

(sut/tick {:time 0} machine
          {:status nil
           :spec {:role "master"
                  :size "1Gi"}
           :kind "PgInstance"})
