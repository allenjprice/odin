(ns odin.routes.api
  (:require [amazonica.aws.s3 :as s3]
            [blueprints.models.account :as account]
            [blueprints.models.property :as property]
            [buddy.auth.accessrules :refer [restrict]]
            [com.walmartlabs.lacinia :refer [execute]]
            [compojure.core :as compojure :refer [defroutes GET POST]]
            [customs.access :as access]
            [datomic.api :as d]
            [odin.aws :as aws]
            [odin.graphql :as graph]
            [odin.graphql.resolvers.utils :as gqlu]
            [odin.routes.kami :as kami]
            [odin.routes.onboarding :as onboarding]
            [odin.routes.util :refer :all]
            [ring.util.response :as response]
            [toolbelt.core :as tb]
            [blueprints.models.application :as application]
            [taoensso.timbre :as timbre]
            [toolbelt.datomic :as td]))

;; ==============================================================================
;; helpers ======================================================================
;; ==============================================================================


(defn- content-type [req]
  (get-in req [:headers "accept"] "application/transit+json"))


(defn- s3-image-url [bucket-name creds key]
  (format "https://s3-%s.amazonaws.com/%s/%s" (:endpoint creds) bucket-name key))


;; =============================================================================
;; GraphQL
;; =============================================================================


(defn extract-graphql-expression [request]
  (case (:request-method request)
    :get  [:query (get-in request [:params :query] "")]
    :post [:mutation (get-in request [:params :mutation] "")]))


(defn context [req]
  (gqlu/context
    (->conn req)
    (->requester req)
    (->stripe req)
    (->config req)
    (->teller req)))


(defn result->status [{:keys [errors] :as result}]
  (cond
    (nil? errors)                                      200
    (tb/find-by #(= :unauthorized (:reason %)) errors) 403
    :otherwise                                         400))


(defn graphql-handler
  [schema]
  (fn [req]
    (let [[op expr] (extract-graphql-expression req)
          result    (execute schema
                             (format "%s %s" (name op) expr)
                             nil
                             (context req))]
      (-> (response/response result)
          (response/content-type "application/transit+json")
          (response/status (result->status result))))))


;; =============================================================================
;; History
;; =============================================================================


(defn- query-history
  [db e]
  (d/q '[:find ?attr ?type ?v ?tx-time ?account
         :in $ ?e
         :where
         [?e ?a ?v ?t true]
         [?a :db/ident ?attr]
         [?a :db/valueType ?_type]
         [?_type :db/ident ?type]
         [?t :db/txInstant ?tx-time]
         [(get-else $ ?t :source/account false) ?account]]
       (d/history db) e))


(defn- resolve-value
  [db type value]
  (if (not= type :db.type/ref)
    value
    (let [e (d/entity db value)]
      (or (:db/ident e) value))))


(defn history
  "Produce a list of all changes to entity `e`, the instant at time in which the
  change occurred, and the user that made the change (if present)."
  [db e]
  (->> (query-history db e)
       (mapv
        (fn [[attr type value tx-time account]]
          (let [value   (resolve-value db type value)
                account (when-let [account (d/entity db account)]
                          {:id   (:db/id account)
                           :name (account/short-name account)})]
            (tb/assoc-when
             {:a attr
              :v value
              :t tx-time}
             :account account))))))


;; upload cover photo ===========================================================


(defn- community-exists?
  [db community-id]
  (some?
   (d/q '[:find ?name .
          :in $ ?c
          :where
          [?c :property/name ?name]]
        db community-id)))


(def ^:private images-bucket-name
  "starcity-images")


(def ^:private cover-image-url
  (partial s3-image-url images-bucket-name))


(defn upload-cover-photo!
  [community-id]
  (fn [{:keys [params] :as req}]
    (let [content-type (content-type req)]
      (cond
        (not (community-exists? (->db req) community-id))
        (-> (response/response {:message "Invalid community specified."})
            (response/status 404)
            (response/content-type content-type))

        :otherwise
        (let [{:keys [filename tempfile]} (first (:files params))
              key                         (str "communities/covers/" community-id "/" filename)]
          (s3/put-object aws/creds
                         :bucket-name images-bucket-name
                         :key key
                         :file tempfile)
          @(d/transact (->conn req) [[:db/add
                                      community-id
                                      :property/cover-image-url
                                      (cover-image-url aws/creds key)]])
          (-> (response/response {:message "ok"})
              (response/content-type content-type)))))))


;; income verification ==========================================================


(def ^:private income-bucket-name
  "starcity-income-verification")


(def ^:private income-file-url
  (partial s3-image-url income-bucket-name))


(defn- create-income-file-tx
  "Create transaction information to add income file to application"
  [filename uri]
  {:db/id     (d/tempid :db.part/starcity)
   :file/name filename
   :file/uri  uri})


(defn- upload-income-file [conn application-id {:keys [filename tempfile]}]
  (let [key            (str "applications/" application-id "/" filename)
        income-file-tx (create-income-file-tx filename (income-file-url aws/creds key))]
    (s3/put-object aws/creds
                   :bucket-name income-bucket-name
                   :key key
                   :file tempfile)
    @(d/transact conn [{:db/id
                        application-id
                        :application/income
                        income-file-tx}])))


(defn- upload-income-file!
  [application-id]
  (fn [{:keys [params] :as req}]
    (let [requester    (->requester req)
          application  (d/entity (->db req) application-id)
          content-type (content-type req)]
      (cond
        (nil? application)
        (-> (response/response {:message "That's not a real application."})
            (response/status 404)
            (response/content-type content-type))

        (not= requester (application/account application))
        (-> (response/response
             {:message "You are not authorized to upload income to this application"})
            (response/status 403)
            (response/content-type content-type))

        :otherwise
        (let [f     (:files params)
              files (if (vector? (first f)) (first f) f)]
          (doseq [f files]
            (upload-income-file (->conn req) (td/id application) f))
          (-> (response/response {:message "ok"})
              (response/content-type content-type)))))))


;; =============================================================================
;; Routes
;; =============================================================================


(defroutes routes

  (compojure/context "/onboarding" []
    (restrict onboarding/routes
      {:handler  {:and [access/authenticated-user (access/user-isa :account.role/onboarding)]}
       :on-error (fn [_ _] {:status 403 :body "You are not authorized."})}))

  (GET "/graphql" [] (graphql-handler graph/schema))
  (POST "/graphql" [] (graphql-handler graph/schema))

  (GET "/income/:file-id" [file-id]
       (-> (fn [req]
             (let [file (d/entity (d/db (->conn req)) (tb/str->int file-id))]
               (response/file-response (:income-file/path file))))
           (restrict {:handler {:and [access/authenticated-user
                                      (access/user-isa :account.role/admin)]}})))

  (POST "/applications/:application-id/verify-income" [application-id]
        (-> (upload-income-file! (tb/str->int application-id))
            (restrict {:handler {:and [access/authenticated-user
                                       (access/user-isa :account.role/applicant)]}})))

  (GET "/history/:entity-id" [entity-id]
       (fn [req]
         (let [db (d/db (->conn req))]
           (-> (response/response {:data {:history (history db (tb/str->int entity-id))}})
               (response/content-type "application/transit+json")))))


  (POST "/communities/:community-id/cover-photo" [community-id]
        (-> (upload-cover-photo! (tb/str->int community-id))
            (restrict {:handler (access/user-isa :account.role/admin)})))


  (compojure/context "/kami" [] kami/routes))
