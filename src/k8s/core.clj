(ns k8s.core
  (:import java.util.Base64)
  (:require
   [org.httpkit.client :as http-client]
   [clj-json-patch.core :as patch]
   [clojure.walk :as walk]
   [unifn.core :as u]
   [cheshire.core :as json]
   [clojure.tools.logging :as log]
   [clojure.string :as str]))

(def default-headers
  (if-let [token (System/getenv "KUBE_TOKEN")]
    {"Authorization" (str "Bearer " token)}
    {}))

(def kube-url (or (System/getenv "KUBE_URL") "http://localhost:8001"))

(defn url [cfg pth]
  (str kube-url "/" pth))

(defn curl [cfg pth]
  (let [res @(http-client/get
              (url cfg pth)
              {:headers default-headers :insecure? true})]
    (-> res :body)))

(defn base64-decode [s]
  (if s
    (String. (.decode (Base64/getDecoder) s))
    nil))

(defn secret [name key]
  (let [cfg {:apiVersion "v1" :ns "default"}]
    (->
     @(http-client/get
       (str kube-url  "/api/v1/namespaces/default/secrets/" name)
       {:insecure? true
        :headers (merge default-headers {"Content-Type" "application/json-patch+json"})})
     :body (json/parse-string keyword)
     :data key
     base64-decode)))

(defn resolve-secrets [res]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (contains? x :valueFrom))
       (if-let [{nm :name k :key} (get-in x [:valueFrom :secretKeyRef])]
         (if-let [res (secret nm (keyword k))]
           res
           (do (log/warn "Could not resolve secret " k " from " nm) x))
         x)
       x)) res))

(defmethod
  u/*fn
  ::resolve-secrets
  [res]
  (resolve-secrets res))

(defn resource-url [cfg & parts]
  (url cfg (str (if (re-matches  #"^v[.0-9]+$" (:apiVersion cfg))
                  "api" "apis")
                "/" (:apiVersion cfg)
                (when (:ns cfg) (str "/namespaces/" (:ns cfg)))
                (str "/" (or (:plural cfg) (str (str/lower-case (:kind cfg)) "s")))
                (when (seq? parts)
                  (str "/" (str/join "/" parts))))))

(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1"})
(resource-url {:kind "PersistentVolumeClaim" :apiVersion "apiextensions.k8s.io/v1beta1"})

(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1" :ns "test"})
(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1" :ns "test"})


(defn query [cfg & pth]
  (println  (apply resource-url cfg pth))
  (let [res @(http-client/get
              (apply resource-url cfg pth)
              {:headers (merge default-headers {"Content-Type" "application/json"})
               :insecure? true})]
    (-> res
     :body
     (json/parse-string keyword)
     (resolve-secrets))))

(first (:items (query {:apiVersion "v1" :kind "PersistentVolumeClaim"})))

(defn list [cfg] (query cfg))

(defn find [cfg]
  (query cfg (or (:id cfg) (get-in cfg [:metadata :name]))))

(defn create [res]
  (let [u (resource-url res)]
    (println u)
    (-> @(http-client/post
          u
          {:body (json/generate-string (walk/stringify-keys res))
           :insecure? true
           :headers (merge default-headers {"Content-Type" "application/json"})})
        :body
        (json/parse-string))))

(defn delete [res]
  (-> @(http-client/delete
        (str (resource-url res (get-in res [:metadata :name])))
        {:headers (merge default-headers {"Content-Type" "application/json"})
         :insecure? true})
      :body
      (json/parse-string)))

(defn patch [nres]
  (let [res (find nres)]
    (if-not (or (number? res) (= "Failure" (get res "status")))
      (let [diff (patch/diff res (merge res (walk/stringify-keys nres)))]
        (->
         @(http-client/patch
           (str (resource-url res (get-in res [:metadata :name])))
           {:body (json/generate-string diff)
            :insecure? true
            :headers (merge default-headers {"Content-Type" "application/json-patch+json"})})
         :body))
      res)))

(comment
  ;; (map :resourceVersion (map :metadata (:items (list cfg :repositories))))
  ;; (map :metadata)

  ;; (find cfg :repositories "ci3")

  ;; (resolve-secrets {:tar{:foo {:valueFrom {:secretKeyRef {:name "ci3" :key "bbKey"}}}}})
  ;; (resolve-secrets (find cfg :repositories "ci3"))

  ;; (patch cfg :repositories "ci3-chart" {:webhook nil})

  ;; (secret "ci3" :mySecret)
  ;; (secret "secrets" :github_token)

  ;; (secret "bitbucket" :oauthConsumerSecret)

  ;; (find cfg :builds "ci3-build-6")

  ;; (patch cfg :builds "test-1" {:status "changed"})

  ;; (delete cfg :repositories "ci3")


  ;; (curl {} "api/v1/namespaces/default/pods/aitem-hook-test-d40a2375646990b2dec75e80cf97ce5a8a77a199/log")

  ;; (create cfg :builds
  ;;         {:kind "Build"
  ;;          :apiVersion "ci3.io/v1"
  ;;          :metadata {:name "test-00"}})
  ;; (-> @(http-client/get
  ;;       (url cfg (str "api/v1/namespaces/default/pods"))
  ;;       {:insecure? true
  ;;        :headers (merge default-headers {"Content-Type" "application/json"})})
  ;;     :body
  ;;     (json/parse-string))

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")



  (require '[clj-yaml.core :as yaml])

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :pods)))

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :persistentvolumes)))
  )
