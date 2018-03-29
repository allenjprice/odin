(ns member.services.views
  (:require [antizer.reagent :as ant]
            [iface.components.form :as form]
            [iface.components.services :as services]
            [iface.components.typography :as typography]
            [iface.utils.formatters :as format]
            [member.content :as content]
            [member.routes :as routes]
            [member.services.db :as db]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [taoensso.timbre :as timbre]
            [toolbelt.core :as tb]))


(defn menu []
  (let [section (subscribe [:services/section])
        count (subscribe [:services.cart/item-count])]
    [ant/menu {:mode          :horizontal
               :selected-keys [@section]
               :on-click      #(dispatch [:services.section/select
                                          (aget % "key")])}
     [ant/menu-item {:key "book"
                     :class "book-services"}
      "Book services"]
     [ant/menu-item {:key "cart"}
      [ant/icon {:type "shopping-cart"}] @count]
     [ant/menu-item {:key "history" :style {:float "right"}} "Order history"]
     [ant/menu-item {:key "subscriptions" :style {:float "right"}} "Subscriptions"]
     [ant/menu-item {:key "active-orders" :style {:float "right"}} "Active orders"]]))


;; ==============================================================================
;; BOOK SERVICES ================================================================
;; ==============================================================================


(defn category-icon [{:keys [category label]}]
  (let [selected (subscribe [:services.book/category])
        route    (subscribe [:services.book.category/route category])]
    [:div.category-icon.column
     {:class (when (= category @selected) "is-active")}
     [:a {:href @route}
      [:img {:src "http://via.placeholder.com/150x150"}]
      [:p label]]]))


(defn categories []
  (let [categories (subscribe [:services.book/categories])]
    [:div.container.catalogue-menu
     [:div.columns
      (doall
       (map-indexed
        #(with-meta [category-icon %2] {:key %1})
        @categories))]]))


(defn service-item [{:keys [name description price] :as service}]
  [ant/card
   [:div.service
    [:div.columns
     [:div.column.is-3
      [:h4.subtitle.is-5 {:dangerouslySetInnerHTML {:__html name}}]]
     [:div.column.is-6
      [:p.fs3 description]]
     [:div.column.is-1
      [:p.price (if (some? price)
                  (format/currency price)
                  (format/currency 0))]]
     [:div.column.is-2
      [ant/button
       {:on-click #(dispatch [:services.add-service/show service])}
       "Request Service"]]]]])


(defn catalog [services]
  (let [selected @(subscribe [:services.book/category])]
    [:div.catalogue
     [:div.colums {:style {:margin-bottom "0px"}}
      [:div.colums.is-10
       [:h3.title.is-4 (clojure.string/capitalize (name selected)) " Services"]]
      (doall
       (map-indexed #(with-meta [service-item %2] {:key %1}) services))]]))


(defn shopping-cart-button []
  (let [item-count (subscribe [:services.cart/item-count])
        total-cost (subscribe [:services.cart/total-cost])]
    [ant/affix {:offsetBottom 20}
     [:div.has-text-right
      [ant/button
       {:size :large
        :type :primary
        :class "ant-btn-xl"
        :on-click #(dispatch [:services.section/select "cart"])}
       "Checkout - $" @total-cost " (" @item-count ")"]]]))


;; ==============================================================================
;; service/order fields =========================================================
;; ==============================================================================

;; TODO make it look pretty?

(defmulti field-value (fn [k value options] k))


(defmethod field-value :time [k value options]
  [:span
   [:p.fs3 (format/time-short value)]])


(defmethod field-value :date [k value options]
  [:span
   [:p.fs3 (format/date-short value)]])


(defmethod field-value :variants [k value options]
  (let [vlabel (reduce (fn [v option] (if (= (keyword value) (:key option)) (:label option) v)) nil options)]
    [:span
     [:p.fs3 vlabel]]))


(defmethod field-value :text [k value options]
  [:span
   [:p.fs3 value]])


(defmethod field-value :number [k value options]
  [:span
   [:p.fs3 value]])


(defmethod field-value :dropdown [k value options]
  [:span
   [:p.fs3 value]])


(defn fields-data-row [fields]
  [:div.columns
   (map-indexed
    (fn [i {:keys [label value type options] :as field}]
      ^{:key i}
      [:div.column.is-half.cart-item-info
       [:span
        [:p.fs3.bold label]]
       [field-value (keyword (name type)) value options]])
    fields)])


(defn fields-data [fields]
  [:div.cart-item
   (map-indexed
    (fn [i field-pair]
      ^{:key i}
      [fields-data-row field-pair])
    (partition 2 2 nil fields))])


;; ==============================================================================
;; shopping cart ================================================================
;; ==============================================================================


(defn cart-item [{:keys [index name description price fields]}]
  (let [service-item {:index       index
                      :name        name
                      :description description
                      :price       price}]
    [ant/card {:style {:margin "10px 0"}}
     [:div.columns
      [:div.column.is-6
       [:h4.subtitle.is-5 name]]
      [:div.column.is-2
       [:p.price (if (some? price)
                   (format/currency price)
                   (format/currency 0))]]
      [:div.column.is-2.align-right
       [ant/button {:icon     "edit"
                    :on-click #(dispatch [:services.cart.item/edit service-item fields])}
        "Edit Item"]]
      [:div.column.is-2
       [ant/button
        {:type     "danger"
         :icon     "close"
         :on-click #(dispatch [:services.cart.item/remove index name])}
        "Remove item"]]]
     (when-not (empty? fields)
       [fields-data (sort-by :index fields)])]))


(defn shopping-cart-footer [requester]
  (let [has-card   (subscribe [:payment-sources/has-card? (:id requester)])
        submitting (subscribe [:ui/loading? :services.cart/submit])]
    [:div.cart-footer.has-text-right
     [:p.fs2
      [:b "NOTE: "] "Premium Service requests are treated as individual billable items. You will be charged for each service as it is fulfilled."]
     [ant/button {:class    "ant-btn-xl"
                  :type     "primary"
                  :on-click #(if-not @has-card
                               (dispatch [:modal/show :payment.source/add])
                               (dispatch [:services.cart/submit requester]))
                  :loading  @submitting}
      "Submit orders"]]))


(defn shopping-cart-body [cart-items requester]
  [:div
   (doall
    (map-indexed #(with-meta [cart-item %2] {:key %1}) cart-items))
   [shopping-cart-footer requester]])


(defn empty-cart []
  [:div.empty-cart
   [:p.fs3.bold "There are no premium services selected"]
   [:p.fs3 "Go to "
    [:a {:href "book"} "Book services"] " to add premium services to your requests"]])



;; add credit card modal ========================================================


(defn modal-add-credit-card []
  (let [is-visible (subscribe [:modal/visible? :payment.source/add])]
    (r/create-class
     {:component-will-mount
      (fn [_]
        (dispatch [:stripe/load-scripts "v2"])
        (dispatch [:stripe/load-scripts "v3"]))
      :reagent-render
      (fn []
        [ant/modal
         {:title     "Add credit card"
          :width     640
          :visible   @is-visible
          :on-ok     #(dispatch [:modal/hide :payment.souce/add])
          :on-cancel #(dispatch [:modal/hide :payment.source/add])
          :footer    nil}
         [:div
          (r/as-element (ant/create-form
                         (form/credit-card
                          {:is-submitting @(subscribe [:ui/loading? :services.cart.add.card/save-stripe-token!])
                           :on-add-card   #(dispatch [:services.cart.add.card/save-stripe-token! %])
                           :on-click      #(dispatch [:modal/hide :payment.source/add])})))]])})))


;; ==============================================================================
;; orders generic ===============================================================
;; ==============================================================================


(defn orders-header [type]
  [:div.columns {:style {:padding        "0 1.5rem"
                         :margin-bottom  "0"
                         :text-transform "uppercase"}}
   [:div.column.is-6
    [:h4.subtitle.is-6.bold (str "Requested " type)]]
   [:div.column.is-2
    [:h4.subtitle.is-6.bold "Request date"]]
   [:div.column.is-1
    [:h4.subtitle.is-6.bold "Price"]]
   [:div.column.is-3
    [:h4.subtitle.is-6.bold "Status"]]])


(defn above-the-fold [{:keys [id name created price status]} is-open requester]
  (let [loading    (subscribe [:ui/loading? :services.order/cancel-order])
        account-id (:id requester)
        canceling  (subscribe [:orders/canceling])]
    [:div.columns
     [:div.column.is-6
      [:span [ant/button {:on-click #(swap! is-open not)
                          :icon     (if @is-open "minus" "plus")
                          :style    {:width        "30px"
                                     :align        "center"
                                     :padding      "0px"
                                     :font-size    20
                                     :margin-right "10px"}}]]
      [:span {:style {:display "inline-block"}}
       [:p.body name]]]
     [:div.column.is-2
      [:p.body (format/date-short created)]]
     [:div.column.is-1
      [:p.body (if (some? price)
                 (format/currency price)
                 (format/currency 0))]]
     [:div.column.is-1
      [ant/tag status]]
     [:div.column.is-2.has-text-right
      (when (= status :pending)
        [ant/button
         {:on-click #(dispatch [:services.order/cancel-order id account-id])
          :type     "danger"
          :icon     "close"
          :loading  (if (= @canceling id)
                      @loading
                      false)}
         "Cancel"])]]))


(defn paginated-list [items requester list-item-fn]
  (let [state (r/atom {:current 1})]
    (fn [items]
      (let [{:keys [current]} @state
            items' (->> (drop (* (dec current) 10) items)
                        (take (* current 10)))]
        [:div
         (map
          #(with-meta [list-item-fn % requester] {:key (:id %)})
          items')
         [ant/pagination
          {:style {:margin-top "20px"}
           :current current
           :total (count items)
           :on-change #(swap! state assoc :current %)}]]))))


;; ==============================================================================
;; active orders content ========================================================
;; ==============================================================================


(defn active-orders-header []
  [orders-header "order"])


(defn active-order-item [{:keys [fields] :as order} requester]
  (let [is-open (r/atom false)]
    (fn []
      [ant/card
       (r/as-element [above-the-fold order is-open requester])
       (when @is-open
         [fields-data (sort-by :index fields)])])))


(defn active-orders [orders requester]
  [paginated-list (sort-by :created > orders) requester active-order-item])


;; ==============================================================================
;; manage subscriptions =========================================================
;; ==============================================================================


(defn manage-subscriptions-header []
  [orders-header "subscription"])


;; TODO how to link to payments?
(defn subscription-details [fields payments]
  [:div
   [fields-data fields]
   [:br]
   [:p.fs3 "Go to "
    [:a {:href ""} "Payments History"] " to see payments made for this subscription"]])


(defn active-subscription-item
  [{:keys [payments fields] :as subscription} requester]
  (let [is-open (r/atom false)]
    (fn []
      [ant/card
       (r/as-element [above-the-fold subscription is-open requester])
       (when @is-open
         [subscription-details (sort-by :index fields) (sort-by :created > payments)])])))


(defn active-subscriptions [subscriptions requester]
  [paginated-list (sort-by :created > subscriptions) requester active-subscription-item])


;; ==============================================================================
;; order history ================================================================
;; ==============================================================================


(defn order-history-header []
  [:div.columns {:style {:padding        "0 1.5rem"
                         :margin-bottom  "0"
                         :text-transform "uppercase"}}
   [:div.column.is-4
    [:h4.subtitle.is-6.bold "Service service"]]
   [:div.column.is-2
    [:h4.subtitle.is-6.bold "Completed"]]
   [:div.column.is-2
    [:h4.subtitle.is-6.bold "Date charged"]]
   [:div.column.is-2
    [:h4.subtitle.is-6.bold "Price"]]
   [:div.column.is-2
    [:h4.subtitle.is-6.bold "Status"]]])


(defn order-history-item
  [{:keys [name price status fields] :as subscription}]
  [ant/card
   [:div.columns
    [:div.column.is-4
     [:span [ant/button {:icon "plus"
                         :style {:width        "30px"
                                 :align        "center"
                                 :padding      "0px"
                                 :font-size    20
                                 :margin-right "10px"}}]]
     [:span {:style {:display "inline-block"}}
      [:p.body name]]]
    [:div.column.is-2
     [:p "N/A"]]
    [:div.column.is-2
     [:p "billed date"]]
    [:div.column.is-2
     [:p.body (if (some? price)
                (format/currency price)
                (format/currency 0))]]
    [:div.column.is-2
     [ant/tag status]]]])


(defn order-history-list [history]
  [:div
   (map
    #(with-meta [order-history-item %] {:key (:id %)})
    (sort-by :created > history))])



;; ==============================================================================
;; premium services content =====================================================
;; ==============================================================================



(defmulti content :page)


(defmethod content :services/book [_]
  (let [selected (subscribe [:services.book/category])
        services (subscribe [:services.book/services-by-catalog @selected])]
    [:div
     [services/service-modal
      {:action      "Add"
       :is-visible  @(subscribe [:services.add-service/visible?])
       :service     @(subscribe [:services.add-service/adding])
       :form-fields @(subscribe [:services.add-service/form])
       :can-submit  @(subscribe [:services.add-service/can-submit?])
       :on-cancel   #(dispatch [:services.add-service/close])
       :on-submit   #(dispatch [:services.add-service/add])
       :on-change   #(dispatch [:services.add-service.form/update %1 %2])}]
     [categories]
     [catalog @services]
     [shopping-cart-button]]))


(defmethod content :services/active-orders [{:keys [requester]}]
  (let [orders (subscribe [:orders/active])]
    [:div
     [active-orders-header]
     [active-orders (sort-by :created > @orders) requester]]))


(defmethod content :services/subscriptions [{:keys [requester]}]
  (let [subscriptions (subscribe [:orders/subscriptions])]
    [:div
     [manage-subscriptions-header]
     [active-subscriptions @subscriptions requester]]))


(defmethod content :services/history [_]
  (let [history (subscribe [:orders/history])]
    [:div
     (.log js/console @history)
     [order-history-header]
     [order-history-list @history]
     #_[:h3 "Look at all the things youve ordered, yo"]]))



(defmethod content :services/cart [{:keys [requester] :as route}]
  (let [cart-items (subscribe [:services.cart/cart])]
    [:div
     [modal-add-credit-card]
     [services/service-modal
      {:action      "Edit"
       :is-visible  @(subscribe [:services.add-service/visible?])
       :service     @(subscribe [:services.add-service/adding])
       :form-fields @(subscribe [:services.add-service/form])
       :can-submit  @(subscribe [:services.add-service/can-submit?])
       :on-cancel   #(dispatch [:services.add-service/close])
       :on-submit   #(dispatch [:services.cart.item/save-edit])
       :on-change   #(dispatch [:services.add-service.form/update %1 %2])}]
     (if-not (empty? @cart-items)
       [shopping-cart-body (sort-by :index @cart-items) requester]
       [empty-cart])]))



(defmethod content/view :services [route]
  (let [header  (subscribe [:services/header])
        subhead (subscribe [:services/subhead])]
    [:div
     (typography/view-header @header @subhead)
     [:div.mb3
      [menu]]
     (content route)]))
