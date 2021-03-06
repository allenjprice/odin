(ns odin.graphql.resolvers.property
  (:require [blueprints.models.account :as account]
            [blueprints.models.address :as address]
            [blueprints.models.license :as license]
            [blueprints.models.property :as property]
            [blueprints.models.source :as source]
            [blueprints.models.unit :as unit]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic.api :as d]
            [odin.graphql.authorization :as authorization]
            [odin.util.tipe :as tipe]
            [odin.tipe :refer [tipe]]
            [teller.customer :as tcustomer]
            [teller.property :as tproperty]
            [teller.source :as tsource]
            [toolbelt.core :as tb]
            [toolbelt.datomic :as td]
            [taoensso.timbre :as timbre]))

;; ==============================================================================
;; fields =======================================================================
;; ==============================================================================


(defn license-prices
  [_ _ property]
  (let [ps (:property/licenses property)]
    (filter
     #(let [license (:license-price/license %)]
        (or (:license/available license)
            (nil? (:license/available license))))
     ps)))


(defn tours
  "Is touring enabled?"
  [_ _ property]
  (boolean (:property/tours property)))


(defn has-financials
  "Do we have financial information?"
  [{:keys [teller]} _ property]
  (boolean (:entity (tproperty/by-community teller property))))


(defn get-images
  [images-id]
  (reduce
   (fn [acc [k v]]
     (if-let [url (:url v)]
       (conj acc url)
       acc))
   []
   (tipe/fetch-document tipe images-id)))


(defn get-amenity-doc-id [doc]
  (reduce
   (fn [acc [k v]]
     (if-let [blocks (:blocks v)]
       (conj acc (:document (first blocks)))
       acc))
   []
   doc))


(defn fetch-and-parse-documents [ids]
  (map
   (fn [id]
     (tipe/parse-document (tipe/fetch-document tipe id)))
   ids))


(defn get-amenities-content [docs]
  (reduce
   (fn [acc a]
     (let [amenity {:label (:text a)
                    :icon (get-in a [:icon :url])}]
       (conj acc amenity)))
   []
   docs))


(defn fetch-amenities [amenities-id]
  (let [doc  (tipe/parse-document (tipe/fetch-document tipe amenities-id))]
    (->> (filter (fn [[k v]] (map? v)) doc)
         (get-amenity-doc-id)
         (fetch-and-parse-documents)
         (get-amenities-content))))


(defn application-copy
  "All of the copy and media on this community used for the application process"
  [_ _ property]
  (let [copy-id  (:tipe/document-id property)
        doc      (tipe/fetch-document tipe copy-id)
        app-copy (tipe/parse-document (:ptmInfo doc))]
    {:name         (:name app-copy)
     :images       (get-images (:images app-copy))
     :introduction (:introduction app-copy)
     :building     (:buildingDetails app-copy)
     :neighborhood (:neighborhood app-copy)
     :community    (:yourCommunity app-copy)
     :amenities    (fetch-amenities (:amenities app-copy))}))


(defn- bank-accounts*
  "Given a community entity, fetch all bank accounts associated with this community."
  [teller property]
  (let [teller-property (tproperty/by-community teller property)
        customer        (when (some? (:entity teller-property))
                          (tproperty/customer teller-property))
        sources         (when-some [c customer]
                          (tcustomer/sources c))]
    (when (some? sources)
      (->> (map
            (fn [source]
              {:id       (tsource/id source)
               :verified (= "verified" (tsource/status source))
               :type     (if (some? (tsource/payment-types source))
                           :deposit
                           :ops)})
            sources)
           (into [])))))


(defn bank-accounts
  "Given a community entity, fetch all bank accounts associated with this community."
  [{:keys [teller]} _ property]
  (bank-accounts* teller property))


(defn has-verified-financials
  "Has this community's financial information been verified?"
  [{:keys [teller]} _ property]
  (if-let [bank-accounts (bank-accounts* teller property)]
      (reduce
       (fn [verified-so-far bank-account]
         (and verified-so-far (:verified bank-account)))
       true
       bank-accounts)
      false))


;; ==============================================================================
;; mutations ====================================================================
;; ==============================================================================


(defn update-existing [unit term rate]
  (when-let [lp (tb/find-by (comp #{term} :license/term :license-price/license)
                            (:property/licenses unit))]
    {:db/id               (:db/id lp)
     :license-price/price rate}))


(defn create-new [db unit term rate]
  {:db/id         (:db/id unit)
   :unit/licenses {:license-price/price   rate
                   :license-price/license (:db/id (license/by-term db term))}})


(def allowed-term?
  #{3 6 12})


(defn set-rate!
  "Set the rate for the property for the given term."
  [{:keys [conn requester]} {:keys [id term rate]} _]
  (when-not (allowed-term? term)
    (resolve/resolve-as nil {:message (format "'%s' is not a valid term length!")
                             :term    term}))
  (let [property (d/entity (d/db conn) id)]
    @(d/transact conn [(or (update-existing property term rate)
                           (create-new property term rate))
                       (source/create requester)])
    (d/entity (d/db conn) id)))


(defn toggle-touring!
  "Toggle a property's `:property/tours` attribute on/off."
  [{:keys [conn requester]} {:keys [id]} _]
  (let [property (d/entity (d/db conn) id)]
    @(d/transact conn [[:db/add id :property/tours (not (:property/tours property))]])
    (d/entity (d/db conn) id)))


;; add financial info ===========================================================


(def ^:private account-holder-email
  "jesse@starcity.com")


(def ^:private business-address
  (tproperty/address "1020 Kearny St" "San Francisco" "CA" "94133"))


(defn- business [{:keys [business_name tax_id]} owner address]
  (tproperty/business business_name tax_id owner address))


(defn- bank-account [{:keys [account_number routing_number account_type account_holder]}]
  (tproperty/bank-account account_number routing_number account_type account_holder))


(defn- owner [{:keys [first_name last_name dob ssn]}]
  (tproperty/owner first_name last_name dob ssn))


(defn add-financial-info!
  [{:keys [conn teller]} {:keys [params id]} _]
  (let [community (d/entity (d/db conn) id)
        owner     (owner params)
        business  (business params owner business-address)
        bdeposit  (bank-account (:deposit params))
        bops      (bank-account (:ops params))]
    (tproperty/create! teller (property/code community) (property/name community) account-holder-email
                       {:deposit   (tproperty/connect-account business bdeposit "daily")
                        :ops       (tproperty/connect-account business bops "daily")
                        :community community})
    (d/entity (d/db conn) (td/id community))))


(defn verify-financial-info!
  [{:keys [conn teller]} {:keys [params id]}]
  ;;TODO - your code here!
  (resolve/resolve-as nil {:message "this mutation has not been implemented yet!"}))


;; create =======================================================================


(defn- parse-license-prices [db license-prices]
  (->> (map
        (fn [lprices]
          (tb/transform-when-key-exists lprices
            {:term  #(:db/id (license/by-term db %))
             :price #(float %)}))
        license-prices)
       (filter :term)))


(defn- parse-create-params [db {:keys [address] :as params}]
  (tb/transform-when-key-exists params
    {:units          #(unit/create-community-units (:code params) %)
     :license_prices #(property/create-license-prices (parse-license-prices db %))
     :address        (fn [{:keys [lines locality region country postal_code]
                           :or   {country "US"}}]
                       (address/create lines locality region country postal_code))}))


(defn create!
  "Create a new community"
  [{:keys [conn requester]} {params :params} _]
  (let [{:keys [name code units address available_on license_prices]}
        (parse-create-params (d/db conn) params)]
    @(d/transact conn [(property/create name code units available_on license_prices
                                        :address address)
                       (source/create requester)])
    (d/entity (d/db conn) [:property/code code])))


;; ==============================================================================
;; queries ======================================================================
;; ==============================================================================


(defn entry
  "Look up a single property by id."
  [{conn :conn} {id :id} _]
  (d/entity (d/db conn) id))


(defn query
  [{conn :conn} _ _]
  (->> (d/q '[:find [?e ...]
              :where
              [?e :property/code _]]
            (d/db conn))
       (map (partial d/entity (d/db conn)))))


;; ==============================================================================
;; resolvers ====================================================================
;; ==============================================================================


(defmethod authorization/authorized? :property/create! [_ account _]
  (account/admin? account))


(defmethod authorization/authorized? :property/set-rate! [_ account _]
  (account/admin? account))


(defmethod authorization/authorized? :property/toggle-touring! [_ account _]
  (account/admin? account))


(def resolvers
  {;; fields
   :property/license-prices          license-prices
   :property/tours                   tours
   :property/application-copy        application-copy
   :property/has-financials          has-financials
   :property/has-verified-financials has-verified-financials
   :property/bank-accounts           bank-accounts
   ;; mutations
   :property/add-financial-info!     add-financial-info!
   ;; :property/verify-financial-info!  verify-financial-info!
   :property/create!                 create!
   :property/set-rate!               set-rate!
   :property/toggle-touring!         toggle-touring!
   ;; queries
   :property/entry                   entry
   :property/query                   query})
