(ns member.services.subs
  (:require [clojure.string :as string]
            [member.services.db :as db]
            [re-frame.core :refer [reg-sub]]
            [toolbelt.core :as tb]))


(reg-sub
 db/path
 (fn [db _]
   (db/path db)))


(reg-sub
 :services/query-params
 :<- [db/path]
 (fn [db _]
   (:params db)))


(reg-sub
 :services/section
 :<- [:route/current]
 (fn [{page :page} _]
   (name page)))


(reg-sub
 :services/header
 :<- [:route/current]
 (fn [{page :page} _]
   (case page
     :services/book          "Premium Services"
     :services/cart          "Shopping Cart"
     :services/active-orders "Requested Services"
     :services/subscriptions "Active Subscriptions"
     :services/history       "Order History"
     "Premium Services")))


;; NOTE We need better subheads for these sections
(reg-sub
 :services/subhead
 :<- [:route/current]
 (fn [{page :page} _]
   (case page
     :services/book          "Browse and order premium services"
     :services/cart          "Review and confirm your order"
     :services/active-orders "Manage your active requests"
     :services/subscriptions "Manage your current subscriptions"
     :services/history       "Look at all the things you've ordered"
     "")))


(reg-sub
 :services.book/categories
 :<- [db/path]
 (fn [db _]
   (-> (reduce (fn [catalogs c]
                 (conj catalogs {:category c :label (string/capitalize (name c))}))
               [{:category :all
                 :label    "All"}]
               (:catalogs db))
       (conj {:category :miscellaneous
              :label    "Miscellaneous"}))))


(reg-sub
 :services.book/category
 :<- [db/path]
 (fn [db _]
   (get-in db [:params :category])))


(reg-sub
 :services.book.category/route
 :<- [:services/query-params]
 :<- [:route/current]
 (fn [[query-params route] [_ category]]
   (db/params->route (:page route)
                     (assoc query-params :category category))))


;; do we still need this?
;; gets a category id and checks if it has more than 2 items in it
(reg-sub
 :services.book.category/has-more?
 :<- [db/path]
 (fn [db [_ id]]
   (let [catalogue (first (filter #(= (:id %) id) (:catalogues db)))]
     (> (count (:items catalogue)) 2))))


(reg-sub
 :services.add-service/adding
 :<- [db/path]
 (fn [db _]
   (:adding db)))


(reg-sub
 :services.add-service/form
 :<- [db/path]
 (fn [db _]
   (:form-data db)))


(reg-sub
 :services.add-service/visible?
 :<- [:modal/visible? db/modal]
 (fn [is-visible _]
   is-visible))


(reg-sub
 :services.add-service/required-fields
 :<- [:services.add-service/form]
 (fn [form _]
   (into [] (filter #(:required %) form))))


(reg-sub
 :services.add-service/can-submit?
 :<- [:services.add-service/form]
 :<- [:services.add-service/required-fields]
 (fn [[form required-fields] _]
   (reduce (fn [all-defined field]
             (and all-defined (:value field))) true required-fields)))


(reg-sub
 :services.book/services-by-catalog
 :<- [db/path]
 (fn [db [_ selected]]
   (case selected
     :all (:services db)
     :miscellaneous (get
                     (group-by #(empty? (:catalogs %)) (:services db))
                     true)
     (get
      (group-by #(some (fn [c] (= c selected)) (:catalogs %)) (:services db))
      true))))


(reg-sub
 :services.cart/cart
 :<- [db/path]
 (fn [db _]
   (:cart db)))


(reg-sub
 :services.cart/item-count
 :<- [:services.cart/cart]
 (fn [db _]
   (count db)))


(reg-sub
 :services.cart/total-cost
 :<- [:services.cart/cart]
 (fn [cart _]
   (reduce #(+ %1 (:price %2)) 0 cart)))


(reg-sub
 :orders/active
 :<- [db/path]
 (fn [db _]
   (filter #(and (not= (:billed %) :service.billed/monthly) (= (:status %) :pending)) (:orders db))))


(reg-sub
 :orders/subscriptions
 :<- [db/path]
 (fn [db _]
   (filter #(and (= (:billed %) :service.billed/monthly) (not= (:status %) :cancelled)) (:orders db))))


(reg-sub
 :orders/history
 :<- [db/path]
 (fn [db _]
   (filter #(not= (:status %) :pending) (:orders db))))
