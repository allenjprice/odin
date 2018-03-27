(ns admin.services.events
  (:require [admin.services.db :as db]
            [admin.services.orders.events]
            [admin.routes :as routes]
            [re-frame.core :refer [reg-event-db
                                   reg-event-fx
                                   path]]
            [toolbelt.core :as tb]
            [iface.utils.norms :as norms]
            [antizer.reagent :as ant]))



;; ==============================================================================
;; list =========================================================================
;; ==============================================================================

(reg-event-fx
 :services/query
 [(path db/path)]
 (fn [{db :db} [k params]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query      [[:services {:params params}
                             [:id :name :code :catalogs :price]]]
               :on-success [::services-query k params]
               :on-failure [:graphql/failure k]}}))

(reg-event-fx
 ::services-query
 [(path db/path)]
 (fn [{db :db} [_ k params response]]
   {:db (->> (get-in response [:data :services])
             (norms/normalize db :services/norms))
    :dispatch [:ui/loading k false]}))



(reg-event-fx
 :services.search/change
 [(path db/path)]
 (fn [{db :db} [_ text]]
   {:db (assoc db :search-text text)}))


(defmethod routes/dispatches :services/list
  [route]
  [[:services/query]
   [:properties/query]])


;; ====================================================
;; entry
;; ====================================================


(reg-event-fx
 ::set-initial-service-id
 [(path db/path)]
 (fn [{db :db} [k service-id]]
   {:db (assoc db
               :service-id (tb/str->int service-id)
               :from       (-> (js/moment (.now js/Date))
                               (.subtract 1 "months")
                               (.hour 0)
                               (.minute 0)
                               (.second 0)
                               (.toISOString))
               :to         (-> (js/moment (.now js/Date))
                               (.hour 23)
                               (.minute 59)
                               (.second 59)
                               (.toISOString)))}))


(reg-event-fx
 :service/fetch
 [(path db/path)]
 (fn [{db :db} [k service-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query      [[:service {:id service-id}
                             [:id :name :description :active :code :price :cost :billed :rental :catalogs
                              [:fields [:id :index :type :label :required
                                        [:options [:index :value :label]]]]
                              [:properties [:id]]
                              [:variants [:id :name :cost :price]]]]
                            [:orders {:params {:services [service-id]
                                               :datekey  :created
                                               :from     (:from db)
                                               :to       (:to db)}}
                             [:id]]]
               :on-success [::service-fetch-success k]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::service-fetch-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   (let [service (get-in response [:data :service])
         order-count (count (get-in response [:data :orders]))]
     {:db (norms/assoc-norm db :services/norms (:id service) (assoc service :order-count order-count :properties (mapv :id (:properties service))))
      :dispatch [:ui/loading k false]})))


(reg-event-fx
 :service.range/change
 [(path db/path)]
 (fn [{db :db} [_ from to]]
   {:db (assoc db :from from :to to)
    :dispatch [:service/fetch (:service-id db)]}))


(reg-event-fx
 :service.range/close-picker
 [(path db/path)]
 (fn [{db :db} _]
   {:db (assoc db :picker-visible false)}))

(defmethod routes/dispatches :services/entry
  [route]
  (let [service-id (get-in route [:params :service-id])]
    [[::set-initial-service-id service-id]
     [:service/fetch (tb/str->int service-id)]
     [:services/query]
     [:properties/query]
     [:service/cancel-edit]]))


;; ==============================================================================
;; editing ======================================================================
;; ==============================================================================
(reg-event-fx
 :service/edit-service
 [(path db/path)]
 (fn [{db :db} [_ service]]
   {:dispatch-n [[:service/toggle-is-editing true]
                 [:service.form/populate service]]}))

(reg-event-db
 :service/toggle-is-editing
 [(path db/path)]
 (fn [db [_ val]]
   (assoc db :is-editing val)))

(reg-event-fx
 :service/cancel-edit
 [(path db/path)]
 (fn [{db :db} _]
   {:dispatch-n [[:service.form/clear]
                 [:service/toggle-is-editing false]]}))

;; send the entire form to graphql. let the resolver determine which attrs to update
(reg-event-fx
 :service/save-edits
 [(path db/path)]
 (fn [{db :db} [k service-id form]]
   (js/console.log "updating service " service-id)
   (js/console.log "and my form says" form)
   {:graphql {:mutation
              [[:service_update {:service_id service-id
                                 :params  form}
                [:id]]]
              :on-success [::update-success k]
              :on-failure [:graphql/failure k]}}
   ))

(reg-event-fx
 ::update-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   {:dispatch-n [[:services/query]
                 [:service.form/hide]
                 [:service.form/clear]]
    :notification [:success "Your changes have been saved!"]
    :route (routes/path-for :services/entry :service-id (str (get-in response [:data :service_update :id])))}))



;; ==============================================================================
;; copy service =================================================================
;; ==============================================================================


(defn copy-service-data
  [service]
  (tb/transform-when-key-exists service
    {:name     #(str % " - copy")
     :code     (constantly "")
     :catalogs (partial mapv clojure.core/name)
     :fields   (partial mapv #(-> (dissoc % :id)
                           (update :options vec)))}))


(reg-event-fx
 :service/copy-service
 [(path db/path)]
 (fn [{db :db} [_ service]]
   {:dispatch-n [[:service.form/populate (copy-service-data service)]
                 [:modal/show :service/create-service-form]]}))


;; ==============================================================================
;; create =======================================================================
;; ==============================================================================

(reg-event-fx
 :service.form/show
 (fn [_ _]
   {:dispatch [:modal/show :service/create-service-form]}))

(defn vecify-fields
  "ensure that the list forms returned from graphql are turned into vecs"
  [fields]
  (mapv
   (fn [f]
     (if (not (nil? (:options f)))
       (assoc f :options (vec (:options f)))))
   fields))

(reg-event-db
 :service.form/populate
 [(path db/path)]
 (fn [db [_ service]]
   (if (not (nil? service))
     (let [{:keys [name description code active properties catalogs price cost billed rental fields]} service]
       (assoc db :form {:name name
                        :description description
                        :code code
                        :active active
                        :properties properties
                        :catalogs (map clojure.core/name catalogs)
                        :price price
                        :cost cost
                        :billed billed
                        :rental rental
                        :fields (if (nil? fields)
                                  []
                                  (vecify-fields fields))}))
     (assoc db :form db/form-defaults))))


(reg-event-fx
 :service.form/hide
 (fn [_ _]
   {:dispatch-n [[:service.form/populate nil]
                 [:modal/hide :service/create-service-form]]}))

(defmulti construct-field
  (fn [_ type]
    type))


(defmethod construct-field :default
  [index type]
  {:index    index
   :type     type
   :label    ""
   :required false})


(defmethod construct-field :dropdown
  [index type]
  {:index    index
   :type     type
   :label    ""
   :required false
   :options  []})


(reg-event-db
 :service.form.field/create
 [(path db/path)]
 (fn [db [_ field-type]]
   (let [new-field (construct-field (count (get-in db [:form :fields])) (keyword field-type))]
     (update-in db [:form :fields] conj new-field))))


(reg-event-db
 :service.form.field/delete
 [(path db/path)]
 (fn [db [_ index]]
   (update-in db [:form :fields] #(->> (tb/remove-at % index)
                                       (map-indexed (fn [i f] (assoc f :index i)))
                                       vec))))
(reg-event-db
 :service.form.field/update
 [(path db/path)]
 (fn [db [_ index key value]]
   (update-in db [:form :fields index] #(assoc % key value))))

(reg-event-db
 :service.form.field/reorder
 [(path db/path)]
 (fn [db [_ index1 index2]]
   (let [field-one (assoc (get-in db [:form :fields index1]) :index index2)
         field-two (assoc (get-in db [:form :fields index2]) :index index1)]
     (-> (assoc-in db [:form :fields index2] field-one)
         (assoc-in    [:form :fields index1] field-two)))))


(reg-event-db
 :service.form.field.option/create
 [(path db/path)]
 (fn [db [_ field-index]]
   (let [new-option
         {:value       ""
          :index       (count (get-in db [:form :fields field-index :options]))
          :field_index field-index}]
     (update-in db [:form :fields field-index :options] conj new-option))))

(reg-event-db
 :service.form.field.option/update
 [(path db/path)]
 (fn [db [_ field-index option-index value]]
   (update-in db [:form :fields field-index :options option-index ] #(assoc % :value value))))


(reg-event-db
 :service.form.field.option/delete
 [(path db/path)]
 (fn [db [_ field-index option-index]]
   (update-in db [:form :fields field-index :options] #(->> (tb/remove-at % option-index)
                                                            (map-indexed (fn [i o] (assoc o :index i)))
                                                            vec))))

(reg-event-db
 :service.form.field.option/reorder
 [(path db/path)]
 (fn [db [_ field-index index1 index2]]
   (let [option-one (assoc (get-in db [:form :fields field-index :options index1]) :index index2)
         option-two (assoc (get-in db [:form :fields field-index :options index2]) :index index1)]
     (-> (assoc-in db [:form :fields field-index :options index2] option-one)
         (assoc-in    [:form :fields field-index :options index1] option-two)))))

(reg-event-fx
 :service.form/update
 [(path db/path)]
 (fn [{db :db} [_ key value]]
   {:db (assoc-in db [:form key] value)}))


(reg-event-db
 :service.form/clear
 [(path db/path)]
 (fn [db _]
   (-> (update-in db [:form] dissoc :name :description :code :properties :catalogs :price :cost :rental :fields)
       (assoc-in [:form] db/form-defaults))))


(reg-event-fx
 :service/create!
 [(path db/path)]
 (fn [{db :db} [k form]]
   (js/console.log form)
   {:graphql {:mutation [[:service_create {:params form}
                          [:id]]]
              :on-success [::create-success k]
              :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::create-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   (js/console.log response)
   {:dispatch-n [[:services/query]
                 [:service.form/hide]
                 [:service.form/clear]]
    :notification [:success "Service created!"]
    :route (routes/path-for :services/entry :service-id (str (get-in response [:data :service_create :id])))}))


(reg-event-fx
 :service/delete!
 [(path db/path)]
 (fn [{db :db} [k service-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation   [[:service_delete {:service service-id}]]
               :on-success [::delete-service-success k service-id]
               :on-failure [:graphql/failure k]}
    :route    (routes/path-for :services/list)}))


(reg-event-fx
 ::delete-service-success
 [(path db/path)]
 (fn [{db :db} [_ k service-id]]
   {:dispatch-n [[:ui/loading k false]
                 [:services/query]]
    :notification [:success "Service deleted."]}))
