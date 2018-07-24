(ns pg3.utils
  (:require [k8s.core :as k8s]
            [pg3.naming :as naming]
            [clojure.string :as str]
            [unifn.core :as u]))

(defn find-pginstances-by-role [instances role]
  (filter #(= role (get-in % [:spec :role])) instances))

(defn find-pginstance-by-role [instances role]
  (first (find-pginstances-by-role instances role)))

(defn pginstances [namespace service-name]
  (let [pginstances (:items (k8s/query {:kind naming/instance-resource-kind
                                        :ns namespace
                                        :apiVersion naming/api}
                                       {:labelSelector
                                        (format "service in (%s)" service-name)}))]
    {:master (find-pginstance-by-role pginstances "master")
     :replicas (find-pginstances-by-role pginstances "replica")
     :all pginstances}))

(defmethod u/*fn ::cluster-active? [{pginstances ::pginstances}]
  (let [{all-pgi :all} pginstances
        phases (mapv #(get-in % [:status :phase]) all-pgi)]
    (when-not (apply = (conj phases "active"))
      {::u/status :stop})))

(defmethod u/*fn ::success [{message ::message}]
  {::u/status :success
   ::u/message message})

(defn read-int [s]
  (Integer/parseInt (str/trim s)))

(defn resource-ok? [resource]
  (every? (partial = "True") (map :status (get-in resource [:status :conditions]))))

(defn resource-errors [resource]
  (->> resource
       ((comp :conditions :status))
       (filter (fn [c] (= (:status c) "False")))
       (mapv :message)))

(defn parse-period [period]
  (let [n (read-int (str/join "" (butlast period)))
        t (last period)]
    (case t
      \s (* n 1000)
      \m (* n 1000 60)
      \h (* n 1000 60 60)
      (throw (Exception. (str "Not supported type: " t))))))

(defn date->string [date]
  (let [tz (java.util.TimeZone/getTimeZone "UTC")
        df (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'")
             (.setTimeZone tz))]
    (.format df date)))

(defn string->date [s]
  (let [tz (java.util.TimeZone/getTimeZone "UTC")
        df (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'")
             (.setTimeZone tz))]
    (.parse df s)))

(defn since [last-update]
  (let [last-update (or (and last-update (string->date last-update))
                        (java.util.Date.))]
    (- (.getTime (java.util.Date.)) (.getTime last-update))))

(defn duration [resource]
  (let [last-update (get-in resource [:status :lastUpdate])
        last-update (or (and last-update (string->date last-update))
                        (java.util.Date.))]
    (- (.getTime (java.util.Date.)) (.getTime last-update))))

(defn now-string []
  (date->string (java.util.Date.)))

(defn timestamp-string []
  (str (.getTime (java.util.Date.))))
