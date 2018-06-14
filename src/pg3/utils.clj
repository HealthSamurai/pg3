(ns pg3.utils
  (:require [pg3.telegram :as t]))

(defn exec-phase [phase f res]
  (println "phase" phase res)
  (try
    (let [{:keys [status text]} (f res)]
      (case status
        :ok    (t/success phase text res)
        :error (t/error phase text res)
        nil))
    (catch Throwable th (t/error phase (.getMessage th) res))))
