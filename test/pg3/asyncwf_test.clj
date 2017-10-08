(ns pg3.asyncwf-test
  (:require [pg3.asyncwf :as sut]
            [clojure.test :refer :all]))

(defn init [arg]
  (println "INIT" arg))

(sut/reg-handler :test :init init)

(defn default [arg]
  (println "No action" arg))

(sut/reg-handler :test :default default)

(defn dispatch [arg]
  (cond
    (= "init" (get-in arg [:state :status])) {:handler :init}
    :else {:handler :default}

    ))

(def machine (sut/build-machine :test #'dispatch))

(machine {:spec {:name "cleo"}
          :status {:status "init"}})

