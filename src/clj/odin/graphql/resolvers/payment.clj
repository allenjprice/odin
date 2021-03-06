(ns odin.graphql.resolvers.payment
  (:require [blueprints.models.account :as account]
            [blueprints.models.events :as events]
            [blueprints.models.order :as order]
            [blueprints.models.payment :as payment]
            [blueprints.models.property :as property]
            [blueprints.models.security-deposit :as deposit]
            [blueprints.models.service :as service]
            [blueprints.models.source :as source]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic.api :as d]
            [odin.graphql.authorization :as authorization]
            [odin.graphql.resolvers.utils :refer [error-message]]
            [taoensso.timbre :as timbre]
            [teller.core :as teller]
            [teller.customer :as tcustomer]
            [teller.payment :as tpayment]
            [teller.property :as tproperty]
            [teller.source :as tsource]
            [toolbelt.core :as tb]
            [toolbelt.date :as date]
            [toolbelt.datomic :as td]))

;; ==============================================================================
;; constants ====================================================================
;; ==============================================================================


(def ^:private late-fee-percent
  "The percentage applied to late rent payments (10%)."
  0.1)


;; ==============================================================================
;; helpers ======================================================================
;; ==============================================================================


(defn- past-first-courtesy?
  "Check if there has been more than one late payment, the first being a courtesy."
  [payments]
  (some? (tb/find-by #(tpayment/overdue? % (tpayment/paid-on %)) payments)))


(defn- should-assess-late-fee?
  "Check if there is more than one late payment and the payment is overdue."
  [teller payment]
  (when (and (or (tpayment/due? payment) (tpayment/failed? payment))
             (tpayment/overdue? payment (java.util.Date.)))
    (let [customer (tpayment/customer payment)
          payments (tpayment/query teller {:customers     [customer]
                                           :payment-types [:payment.type/rent]
                                           :statuses      [:payment.status/paid
                                                           :payment.status/pending]})]
      (past-first-courtesy? payments))))


(defn- payment->order [db payment]
  (if-let [subs (tpayment/subscription payment)]
    (order/by-subscription db subs)
    (order/by-payment db payment)))


;; ==============================================================================
;; fields =======================================================================
;; ==============================================================================


(defn account
  "The account associated with this `payment`."
  [_ _ payment]
  (or (tcustomer/account (tpayment/customer payment))
      (tcustomer/account (tpayment/payee payment))))


(defn amount
  "The amount in dollars on this `payment`."
  [_ _ payment]
  (tpayment/amount payment))


(defn autopay?
  "Is this an autopay `payment`?"
  [_ _ payment]
  (and (some? (tpayment/subscription payment))
       (= :payment.type/rent (tpayment/type payment))))


(defn check
  "The check associated with this `payment`, if any."
  [_ _ payment]
  (tpayment/check payment))


;; TODO: Provide this via some sort of public api
(defn created
  "The instant this `payment` was created."
  [{:keys [teller conn]} _ payment]
  (->> payment teller/entity (td/created-at (d/db conn))))


(defn- is-first-deposit-payment?
  [teller payment]
  (let [customer (tpayment/customer payment)
        payments (filterv
                  (comp nil? tpayment/subtypes)
                  (tpayment/query teller
                                  {:customers     [customer]
                                   :payment-types [:payment.type/deposit]}))]
    (or (= (count payments) 1)
        (= (tpayment/id payment)
           (->> payments
                (map (juxt tpayment/id tpayment/amount))
                (sort-by second <)
                ffirst)))))


(defn- deposit-desc
  "Description for a security deposit `payment`."
  [teller account payment]
  (let [entire-deposit-desc  "Entire security deposit payment"
        partial-deposit-desc "Security deposit installment"
        deposit-refund-desc  "Deposit refund"
        deposit-refund-credit-desc  "Deposit refund credit"
        deposit-refund-charge-desc  "Deposit refund charge"
        deposit              (deposit/by-account account)]
    (cond
      (= :deposit-refund (first (tpayment/subtypes payment)))
      deposit-refund-desc

      (= :deposit-refund-credit (first (tpayment/subtypes payment)))
      deposit-refund-credit-desc

      (= :deposit-refund-charge (first (tpayment/subtypes payment)))
      deposit-refund-charge-desc

      (= :deposit.type/full (deposit/type deposit))
      entire-deposit-desc

      (is-first-deposit-payment? teller payment)
      (str "first " partial-deposit-desc)

      :otherwise
      (str "second " partial-deposit-desc))))


(defn- period-desc [db payment]
  (when (and (some? (tpayment/period-start payment))
             (some? (tpayment/period-end payment)))
    (let [customer (tpayment/customer payment)
          account   (tcustomer/account customer)
          property  (account/current-property db account)]
      (->> [(tpayment/period-start payment) (tpayment/period-end payment)]
           (map (comp date/short #(date/tz-uncorrected % (property/time-zone property))))
           (apply format " from %s-%s")))))


(defn description
  "A description of this `payment`. Varies based on payment type."
  [{:keys [teller conn]} _ payment]
  (let [account  (or (-> payment tpayment/customer tcustomer/account)
                     (-> payment tpayment/payee tcustomer/account))
        property (account/current-property (d/db conn) account)]
    (letfn [(-rent-desc [payment]
              (str "Rent" (period-desc (d/db conn) payment)))
            (-order-desc [payment]
              (let [order        (payment->order (d/db conn) payment)
                    service-desc (service/name (order/service order))]
                (str (or (when-let [d (order/summary order)]
                           (format "%s (%s)" d service-desc))
                         service-desc)
                     (period-desc (d/db conn) payment))))
            (-late-fee-desc [payment]
              (let [parent (tpayment/associated-to payment)]
                (format "Late fee (%s)" (-rent-desc parent))))
            (-application-fee-desc [payment]
              (str "Starcity application fee"))
            (-fee-desc [payment]
              (if-let [subtypes (tpayment/subtypes payment)]
                (->> (map name subtypes)
                     (interpose ", ")
                     (apply str)
                     (format "Fee (%s)"))
                "Fee"))]
      (case (tpayment/type payment)
        :payment.type/rent            (-rent-desc payment)
        :payment.type/order           (-order-desc payment)
        :payment.type/deposit         (deposit-desc teller account payment)
        :payment.type/late-fee        (-late-fee-desc payment)
        :payment.type/application-fee (-application-fee-desc payment)
        :payment.type/fee             (-fee-desc payment)
        nil))))


(defn due
  "The instant this `payment` is due."
  [_ _ payment]
  (tpayment/due payment))


(defn late-fee-due
  "Any late fees associated with this `payment`."
  [{teller :teller} _ payment]
  (when (should-assess-late-fee? teller payment)
    (* late-fee-percent (tpayment/amount payment))))


(defn late-fee-paid
  "Any late fees associated with this `payment`."
  [_ _ payment]
  (when-let [apayments (tpayment/associated payment)]
    (->> apayments
         (filter tpayment/late-fee?)
         (map tpayment/amount)
         (apply +))))


(defn method
  "The method with which this `payment` was made."
  [_ _ payment]
  (when-not (#{:payment.status/due} (tpayment/status payment))
    (let [source (tpayment/source payment)]
      (cond
        (tsource/bank-account? source) :ach
        (tsource/card? source)         :card
        (tpayment/check payment)       :check
        :else                          :other))))


(defn order
  "The order associated with this `payment`, if any."
  [{conn :conn} _ payment]
  (payment->order (d/db conn) payment))


(defn paid-on
  "The instant this `payment` was paid."
  [_ _ payment]
  (tpayment/paid-on payment))


(defn period-end
  "The instant the period of this `payment` ends."
  [_ _ payment]
  (tpayment/period-end payment))


(defn period-start
  "The instant the period of this `payment` ends."
  [_ _ payment]
  (tpayment/period-start payment))


(defn property
  "The property associated with the account that made this `payment`, if any."
  [_ _ payment]
  (tproperty/community (tpayment/property payment)))


(defn source
  "The payment source used to make this `payment`, if any."
  [_ _ payment]
  (tpayment/source payment))


(defn status
  "The status of this `payment`."
  [_ _ payment]
  (keyword (name (tpayment/status payment))))


(defn payment-type
  "What is this `payment` for?"
  [_ _ payment]
  (-> (tpayment/type payment)
      name
      (string/replace "-" "_")
      keyword))


(defn subtypes
  "More details for the payment-type of this `payment`."
  [_ _ payment]
  (tpayment/subtypes payment))


;; =============================================================================
;; Queries
;; =============================================================================


(s/def :gql/account integer?)
(s/def :gql/property string?)
(s/def :gql/source string?)
(s/def :gql/source-types vector?)
(s/def :gql/types vector?)
(s/def :gql/from inst?)
(s/def :gql/to inst?)
(s/def :gql/statuses vector?)
(s/def :gql/subtypes vector?)
(s/def :gql/currencies vector?)
(s/def :gql/datekey keyword?)


(defn- parse-gql-params
  [{:keys [teller] :as ctx}
   {:keys [account property source source_types types
           subtypes from to statuses currencies datekey]
    :as   params}]
  (tb/assoc-when
   (assoc params :limit 100)
   :customers (when-let [c (and (some? account) (tcustomer/by-account teller account))]
                [c])
   :payees (when-let [c (and (some? account) (tcustomer/by-account teller account))]
             [c])
   :properties (when-some [p property]
                 [(tproperty/by-community teller p)])
   :sources (when-some [s source]
              [(tsource/by-id teller s)])
   :source-types (when-some [xs source_types]
                   (map #(keyword "payment-source.type" (name %)) xs))
   :types (when-some [xs types]
            (map #(keyword "payment.type" (string/replace (name %) #"-" "_")) xs))
   :statuses (when-some [xs statuses]
               (map #(keyword "payment.status" (name %)) xs))
   ;; NOTE: These don't get set on payments! Not necessary to use for the time
   ;; being...
   ;; :currency (when-let [c (first currencies)]
   ;;             (name c))
   ))


(s/fdef parse-gql-params
        :args (s/cat :ctx map?
                     :params (s/keys :opt-un [:gql/account :gql/property :gql/source :gql/source-types
                                              :gql/types :gql/from :gql/to :gql/statuses :gql/subtypes
                                              :gql/currencies :gql/datekey]))
        :ret :teller.payment/query-params)


;; =============================================================================
;; Query


(defn payments
  "Query payments based on `params`."
  [{:keys [teller] :as ctx} {params :params} _]
  (let [tparams (parse-gql-params ctx params)]
    (try
      (tpayment/query teller tparams)
      (catch Throwable t
        (timbre/error t ::query params)
        (resolve/resolve-as nil {:message  (error-message t)
                                 :err-data (ex-data t)})))))


;; ==============================================================================
;; mutations ====================================================================
;; ==============================================================================


;; helpers =====================================================================


(defn- default-due-date
  "The default due date is the fifth day of the same month as `start` date.
  Preserves the original year, month, hour, minute and second of `start` date."
  [start]
  (let [st (c/to-date-time start)]
    (c/to-date (t/date-time (t/year st)
                            (t/month st)
                            5
                            (t/hour st)
                            (t/minute st)
                            (t/second st)))))


(defn- ensure-payment-allowed
  [payment source]
  (let [retry        (#{:payment.status/due :payment.status/failed}
                      (tpayment/status payment))
        correct-type (#{:payment.type/rent :payment.type/deposit :payment.type/fee :paym}
                      (tpayment/type payment))
        bank         (tsource/bank-account? source)]
    (cond
      (not retry)
      (format "This payment has status %s; cannot pay!" (name (tpayment/status payment)))

      (not (and correct-type bank))
      "Only bank accounts can be used to make this payment.")))


;; create =======================================================================


(defmulti create-payment!* (fn [_ params] (:type params)))


(defmethod create-payment!* :default [{teller :teller} {:keys [amount type account]}]
  (resolve/resolve-as nil {:message (format "Payment creation of type %s not yet supported." type)}))


(defn- default-due-date
  "The default due date is the fifth day of the same month as `start` date.
  Preserves the original year, month, hour, minute and second of `start` date."
  [start]
  (let [st (c/to-date-time start)]
    (c/to-date (t/date-time (t/year st)
                            (t/month st)
                            5
                            (t/hour st)
                            (t/minute st)
                            (t/second st)))))


(defmethod create-payment!* :rent
  [{:keys [teller conn]} {:keys [month amount account]}]
  (when (nil? month)
    (resolve/resolve-as nil {:message "When payment type is rent, month must be specified."}))
  (let [account  (d/entity (d/db conn) account)
        customer (tcustomer/by-account teller account)
        property (tcustomer/property customer)
        tz       (t/time-zone-for-id (tproperty/timezone property))
        start    (date/beginning-of-month month tz)]
    (tpayment/create! customer amount :payment.type/rent
                      {:period [start (date/end-of-month month tz)]
                       :due    (-> (default-due-date start) (date/end-of-day tz))})))


(defmethod create-payment!* :deposit
  [{:keys [conn teller requester]} {:keys [amount account]}]
  (let [account  (d/entity (d/db conn) account)
        deposit  (deposit/by-account account)
        customer (tcustomer/by-account teller account)
        payment  (tpayment/create! customer amount :payment.type/deposit)]
    @(d/transact conn [[:db/add (td/id deposit) :deposit/payments (td/id payment)]
                       (source/create requester)])
    (tpayment/by-id teller (tpayment/id payment))))


(defn create-payment!
  [{:keys [conn] :as ctx} {params :params} _]
  (create-payment!* ctx params))


;; make payments ===============================================================


;; TODO: We'll need to be able to update the fee amount before we make the
;; charge if they're going to be paying with a card


(defn pay-rent!
  [{:keys [requester teller conn] :as ctx} {:keys [id source] :as params} _]
  (let [payment (tpayment/by-id teller id)
        source  (tsource/by-id teller source)]
    (letfn [(-charge! []
              (let [py (tpayment/charge! payment {:source source})]
                @(d/transact conn [(events/rent-payment-made requester (td/id py))])
                py))
            (-late-fee! []
              (when (should-assess-late-fee? teller payment)
                (let [amount (* late-fee-percent (tpayment/amount payment))
                      apy    (tpayment/add-late-fee! payment amount)]
                  (tpayment/charge! apy {:source source}))))
            (-pay! []
              (-late-fee!)
              (-charge!))]
      (try
        (if-let [error (ensure-payment-allowed payment source)]
          (resolve/resolve-as nil {:message error})
          (-pay!))
        (catch Throwable t
          (timbre/error t ::pay-rent {:payment-id id :source-id (tsource/id source)})
          (resolve/resolve-as nil {:message (error-message t)}))))))


(defn pay-deposit!
  [{:keys [conn requester teller] :as ctx} {source-id :source} _]
  (let [source   (tsource/by-id teller source-id)
        customer (tcustomer/by-account teller requester)
        deposit  (deposit/by-account requester)]
    (try
      (if-not (tsource/bank-account? source)
        (resolve/resolve-as nil {:message
                                 "Your deposit can only be paid with a bank account."})
        ;; NOTE: `deposit/amount-remaining` relies on relations established
        ;; between deposit and payment entities. this can be represented using
        ;; teller (rather than outdated `blueprints.models.payment` namespace)
        ;; after importing blueprints models to `odin`.
        (let [payment      (tpayment/create! customer
                                             (deposit/amount-remaining deposit)
                                             :payment.type/deposit
                                             {:source source})
              is-remainder (< (tpayment/amount payment) (deposit/amount deposit))]
          (->> (tb/conj-when
                [{:db/id            (td/id deposit)
                  :deposit/payments (td/id payment)}]
                (if is-remainder
                  (events/remainder-deposit-payment-made requester (tpayment/id payment))
                  (events/deposit-payment-made requester (tpayment/id payment))))
               (d/transact conn)
               (deref))
          (deposit/by-account (d/entity (d/db conn) (td/id requester)))))
      (catch Throwable t
        (timbre/error t ::pay-deposit {:source-id source-id})
        (resolve/resolve-as nil {:message (error-message t)})))))


(defn pay!
  [{:keys [conn requester teller] :as ctx} {:keys [id source] :as params} _]
  (let [payment (tpayment/by-id teller id)
        source  (tsource/by-id teller source)]
    (try
      (cond
        (not (#{:payment.status/due :payment.status/failed} (tpayment/status payment)))
        (let [msg (format "This payment has status %s; cannot pay!"
                          (name (tpayment/status payment)))]
          (resolve/resolve-as nil {:message msg}))

        (and (#{:payment.type/rent :payment.type/deposit} (tpayment/type payment))
             (not (tsource/bank-account? source)))
        (resolve/resolve-as nil {:message "Only bank accounts can be used to make this payment."})

        :otherwise
        (tpayment/charge! payment {:source source}))
      (catch Throwable t
        (timbre/error t ::pay-fee {:payment-id id
                                   :source-id  (tsource/id source)
                                   :subtypes   (tpayment/subtypes payment)})
        (resolve/resolve-as nil {:message (error-message t)})))))


;; =============================================================================
;; Resolvers
;; =============================================================================


(defmethod authorization/authorized? :payment/list [_ account params]
  (or (account/admin? account)
      (= (:db/id account) (get-in params [:params :account]))))


(defmethod authorization/authorized? :payment/pay-rent!
  [{teller :teller} account params]
  (let [payment  (tpayment/by-id teller (:id params))
        customer (tcustomer/by-account teller account)]
    (= customer (tpayment/customer payment))))


(defmethod authorization/authorized? :payment/pay-deposit!
  [{teller :teller} account params]
  (let [source (tsource/by-id teller (:source params))
        customer (tsource/customer source)]
    (= customer (tcustomer/by-account teller account))))


(defmethod authorization/authorized? :payment/pay!
  [{teller :teller} account params]
  (let [payment  (tpayment/by-id teller (:id params))
        customer (tcustomer/by-account teller account)]
    (= customer (tpayment/customer payment))))


(defmethod authorization/authorized? :payment/create!
  [_ account params]
  (account/admin? account))


(def resolvers
  {;; fields
   :payment/id            (fn [_ _ payment] (tpayment/id payment))
   :payment/account       account
   :payment/amount        amount
   :payment/autopay?      autopay?
   :payment/check         check
   :payment/created       created
   :payment/description   description
   :payment/due           due
   :payment/entity-id     (fn [_ _ payment] (td/id payment))
   :payment/late-fee-paid late-fee-paid
   :payment/late-fee-due  late-fee-due
   :payment/method        method
   :payment/order         order
   :payment/paid-on       paid-on
   :payment/pend          period-end
   :payment/pstart        period-start
   :payment/property      property
   :payment/source        source
   :payment/status        status
   :payment/subtypes      subtypes
   :payment/type          payment-type
   ;; queries
   :payment/list          payments
   ;; mutations
   :payment/create!       create-payment!
   :payment/pay-rent!     pay-rent!
   :payment/pay-deposit!  pay-deposit!
   :payment/pay!          pay!})
