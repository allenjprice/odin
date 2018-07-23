(ns odin.graphql.resolvers.payment-source
  (:refer-clojure :exclude [type name])
  (:require [blueprints.models.account :as account]
            [blueprints.models.member-license :as member-license]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic.api :as d]
            [odin.graphql.authorization :as authorization]
            [odin.graphql.resolvers.utils :refer [error-message]]
            [odin.graphql.resolvers.utils.autopay :as autopay-utils]
            [odin.graphql.resolvers.utils.plans :as plans-utils]
            [odin.models.payment-source :as payment-source]
            [taoensso.timbre :as timbre]
            [teller.customer :as tcustomer]
            [teller.payment :as tpayment]
            [teller.plan :as tplan]
            [teller.property :as tproperty]
            [teller.source :as tsource]
            [teller.subscription :as tsubscription]
            [toolbelt.core :as tb]
            [clojure.string :as str]))

;; =============================================================================
;; Fields
;; =============================================================================


(defn id
  [_ _ source]
  (tsource/id source))


(defn account
  "The account that owns this payment source."
  [_ _ source]
  (tcustomer/account (tsource/customer source)))


(defn last4
  "The last four digits of the source's account/card number."
  [_ _ source]
  (tsource/last4 source))


(defn default?
  "Is this source the default source for premium service orders?"
  [_ _ source]
  (some? (:payment.type/order (tsource/payment-types source))))


(defn expiration
  "Returns the expiration date for a credit card. Returns nil if bank."
  [_ _ source]
  (when (tsource/card? source)
    (str (tsource/exp-month source) "/" (tsource/exp-year source))))


(defn customer
  "Returns the customer for a source."
  [_ _ source]
  "some string")


(defn status
  "The status of source."
  [_ _ source]
  (when (tsource/bank-account? source)
    (tsource/status source)))


(defn autopay?
  "Is this source being used for autopay?"
  [{teller :teller} _ source]
  (let [subs (tsubscription/query teller {:payment-types [:payment.type/rent]
                                          :sources                  [source]})]
    (when (not (empty? subs))
      (not (empty? (tb/find-by #(not (tsubscription/canceled? %)) subs))))))


(defn type
  "The type of source, #{:bank :card}."
  [_ _ source]
  (keyword (clojure.core/name (tsource/type source))))


(defn name
  "The name of this source."
  [_ _ source]
  (if (tsource/card? source)
    (tsource/brand source)
    (tsource/bank-name source)))


(defn payments
  "Payments associated with this `source`."
  [{teller :teller} _ source]
  (tpayment/query teller {:sources [source]}))


;; =============================================================================
;; Queries
;; =============================================================================


(defn sources
  "Retrieve payment sources."
  [{:keys [teller]} {:keys [account]} _]
  (when-let [customer (tcustomer/by-account teller account)]
    (tcustomer/sources customer)))


;; =============================================================================
;; Mutations
;; =============================================================================


(defn delete!
  "Delete the payment source with `id`. If the source is a bank account, will
  also delete it on the connected account."
  [{:keys [teller] :as ctx} {id :id} _]
  (tsource/close! (tsource/by-id teller id)))


;; =============================================================================
;; Add Source


(defn- fetch-or-create-customer!
  "Produce the customer for `requester` if there is one; otherwise, createa a
  new customer."
  [teller db account]
  (let [community (account/current-property db account)
        property  (tproperty/by-community teller community)]
    (if-let [customer (tcustomer/by-account teller account)]
      (do
        ;; when the customer isn't connected to this property...
        (when-not (tcustomer/connected? customer property)
          ;; connect it and set this property to the default
          (tcustomer/set-property! customer property))
        (tcustomer/by-account teller account))
      ;; otherwise create it with current property as the default
      (tcustomer/create! teller (account/email account) {:account  account
                                                         :property property}))))


(defn add-source!
  "Add a new source to the requester's Stripe customer, or create the customer
  and add the source if it doesn't already exist."
  [{:keys [teller conn requester] :as ctx} {:keys [token]} _]
  (try
    (let [customer (fetch-or-create-customer! teller (d/db conn) requester)]
      (tsource/add-source! customer token))
    (catch Throwable t
      (timbre/error t ::add-source! {:email (account/email requester)})
      (resolve/resolve-as nil {:message (error-message t)}))))


;; =============================================================================
;; Verify Bank Account

(s/def ::deposit (s/and pos-int? (partial > 100)))
(s/def ::deposits
  (s/cat :deposit-1 ::deposit
         :deposit-2 ::deposit))


(defn- deposits-valid? [deposits]
  (s/valid? ::deposits deposits))


(defn verify-bank!
  "Verify a bank account given the two microdeposit amounts that were made to
  the bank account."
  [{:keys [conn stripe requester teller] :as ctx} {:keys [id deposits]} _]
  (if-not (deposits-valid? deposits)
    (resolve/resolve-as nil {:message  "Please provide valid deposit amounts."
                             :deposits deposits})
    (let [source (tsource/by-id teller id)]
      (try
        (tsource/verify-bank-account! source deposits)
        (catch Throwable t
          (timbre/error t ::verify-bank-account {:email (account/email requester)
                                                 :id id})
          (resolve/resolve-as nil {:message (error-message t)}))))))


;; =============================================================================
;; Set Autopay


(defn- is-bank-id? [source-id]
  (string/starts-with? source-id "ba_"))


(defn set-autopay!
  "Set a source as the autopay source. Source must be a bank account source."
  [{:keys [conn requester teller] :as ctx} {:keys [id]} _]
  (let [customer (tcustomer/by-account teller requester)
        subs     (->> (tsubscription/query teller {:customers     [customer]
                                                   :payment-types [:payment.type/rent]})
                      (tb/find-by tsubscription/active?))]
    (if (some? subs)
      (resolve/resolve-as nil {:message "You're already subscribed to autopay."})
      (try
        (let [source  (tsource/by-id teller id)
              license (member-license/active (d/db conn) requester)
              rate    (member-license/rate license)
              plan    (tplan/create! teller (plans-utils/plan-name teller license) :payment.type/rent rate)]
          (tsubscription/subscribe! customer plan {:source   source
                                                   :start-at (autopay-utils/autopay-start customer)})
          (tsource/by-id teller id))
        (catch Throwable t
          (timbre/error t ::set-autopay! {:email (account/email requester)})
          (resolve/resolve-as nil {:message (error-message t)}))))))


;; =============================================================================
;; Unset Autopay


(defn unset-autopay!
  "Unset a source as the autopay source. Source must be presently used for
  autopay."
  [{:keys [conn requester teller] :as ctx} {:keys [id]} _]
  (let [customer (tcustomer/by-account teller requester)
        subs     (->> (tsubscription/query teller {:customers     [customer]
                                                   :payment-types [:payment.type/rent]})
                      (tb/find-by tsubscription/active?))]
    (if (nil? subs)
      (resolve/resolve-as nil {:message "You're already unsubscribed to autopay."})
      (try
        (tsubscription/cancel! subs)
        (tsource/by-id teller id)
        (catch Throwable t
          (timbre/error t ::set-autopay! {:email (account/email requester)})
          (resolve/resolve-as nil {:message (error-message t)}))))))


;; =============================================================================
;; Set Default Source


(defn set-default!
  "Set a source as the default payment source. The default payment source will
  be used for premium service requests."
  [{:keys [teller]} {:keys [id]} _]
  (tsource/set-default! (tsource/by-id teller id) :payment.type/order))


;; =============================================================================
;; Resolvers
;; =============================================================================


(defmethod authorization/authorized? :payment.sources/list [_ account params]
  (or (account/admin? account) (= (:db/id account) (:account params))))


(def resolvers
  {;; fields
   :payment-source/id              id
   :payment-source/account         account
   :payment-source/last4           last4
   :payment-source/default?        default?
   :payment-source/expiration      expiration
   :payment-source/customer        customer
   :payment-source/status          status
   :payment-source/autopay?        autopay?
   :payment-source/type            type
   :payment-source/name            name
   :payment-source/payments        payments
   ;; queries
   :payment.sources/list           sources
   ;; mutations
   :payment.sources/delete!        delete!
   :payment.sources/add-source!    add-source!
   :payment.sources/verify-bank!   verify-bank!
   :payment.sources/set-autopay!   set-autopay!
   :payment.sources/unset-autopay! unset-autopay!
   :payment.sources/set-default!   set-default!})
