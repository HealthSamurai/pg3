(ns pg3.asyncwf)

(defonce handlers-map
  (atom {}))

(defn reg-handler [ns k f]
  (swap! handlers-map assoc-in [ns k] f))


(defn resolve-handler [ns k]
  (get-in @handlers-map [ns k]))

(defn build-machine [ns dispatcher]
  (fn [arg]
    (let [{k :handler :as res} (dispatcher arg)]
      (println "resolve " [ns k])
      (if-let [h (resolve-handler ns k)]
        (h (merge arg res))
        (println "Could not resolve " k)))))
