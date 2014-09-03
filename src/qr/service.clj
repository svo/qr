(ns qr.service
    (:require [io.pedestal.http :as bootstrap]
              [io.pedestal.http.route :as route]
              [io.pedestal.http.body-params :as body-params]
              [io.pedestal.http.route.definition :refer [defroutes]]
              [ring.util.response :as ring-response]
              [ring.util.request :as ring-request]
              [clojure.data.json :as json]
              [clojure.java.io :as io]
              [selmer.parser :as selmer-parser]
              [clj-time.local :as time]
              [clj-time.format :as time-format]
              [qr.http.header :as header]
              [qr.http.request :as request]
              [qr.io.image :as image]
              [qr.persistence.riak :as persistence]))

(def date-formatter (time-format/formatters :date))

(defn get-home-create
  []
  (selmer-parser/render-file "public/home.html" {
    :generated (time-format/unparse date-formatter (time/local-now))}))

(defn get-home-view
  [request id]
  (selmer-parser/render-file "public/view.html" {
    :generated (time-format/unparse date-formatter (time/local-now))
    :destination (persistence/get-destination-by-id id)
    :short-url (request/get-host-url request id)
    :id id}))

(defn get-response
  [linkHeader linkHeaderValue contentTypeHeader body]
  (ring-response/content-type (ring-response/header
    (ring-response/response body)
      linkHeader linkHeaderValue) contentTypeHeader))

(defn get-text-plain-response
  [id]
  (let [[linkHeader linkHeaderValue] (header/get-png-link-header id)]
    (get-response
      linkHeader linkHeaderValue "text/plain"
      (persistence/get-destination-by-id id))))

(defn get-redirect-response
  [id]
  (let [[linkHeader linkHeaderValue] (header/get-png-link-header id)]
    (ring-response/header
      (ring-response/redirect (persistence/get-destination-by-id id))
      linkHeader linkHeaderValue)))

(defn get-image-png-response
  [request id]
  (let [[linkHeader linkHeaderValue] (header/get-url-link-header id)]
    (get-response
      linkHeader linkHeaderValue "image/png"
      (io/input-stream (image/qr-for (ring-request/request-url request))))))

(defn create-from-json
  [request]
  (let [[linkHeader linkHeaderValue]
      (header/get-png-link-header
        (persistence/create-record (get (:json-params request) :url)))]
    (ring-response/header
      (ring-response/response "")
        linkHeader linkHeaderValue)))

(defn create-from-form
  [request]
  (let [id (persistence/create-record (get (:form-params request) "url"))]
    (let [[linkHeader linkHeaderValue] (header/get-url-link-header id)]
      (ring-response/header (ring-response/redirect
        (str (ring-request/request-url request) "?id=" id))
        linkHeader linkHeaderValue))))

(defn top-level-get
  [request]
  (let [id (get (:query-params request) :id)]
    (if (nil? id)
      (ring-response/response (get-home-create))
      (ring-response/response (get-home-view request id)))))

(defn top-level-get-with-path-id
  [request]
  (let [id (get-in request [:path-params :id])]
      (if (= "text/plain" (get (:headers request) "accept"))
        (get-text-plain-response id)
        (if (nil? (or (= "image/png" (get (:headers request) "accept"))
            (get (:query-params request) :qr)))
          (get-redirect-response id)
          (get-image-png-response request id)))))

(defn top-level-post
  [request]
  (if (= header/FORM_MIME_TYPE (ring-request/content-type request))
    (create-from-form request)
    (create-from-json request)))

(defroutes routes
  [[["/" {:get top-level-get}
     ^:interceptors [(body-params/body-params) bootstrap/html-body]
     ["/:id" {:get top-level-get-with-path-id}]
     ["/" {:post top-level-post}]]]])

(def service {:env :prod
  ::bootstrap/routes routes
  ::bootstrap/resource-path "/public"
  ::bootstrap/type :jetty
  ::bootstrap/port 8080})
