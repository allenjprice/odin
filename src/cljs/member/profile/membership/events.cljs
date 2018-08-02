(ns member.profile.membership.events
  (:require [member.profile.membership.db :as db]
            [member.routes :as routes]
            [re-frame.core :refer [reg-event-db
                                   reg-event-fx
                                   path]]
            [toolbelt.core :as tb]
            [antizer.reagent :as ant]
            [taoensso.timbre :as timbre]
            [iface.utils.formatters :as format]))


;; =============================================================================
;; Routing
;; =============================================================================


(defmethod routes/dispatches :profile/membership [route]
  (let [account-id (get-in route [:requester :id])]
    [[:profile/fetch-account account-id]
     [:member.license/fetch account-id]
     [:payment-sources/fetch account-id]
     [:payments/fetch account-id]]))


;; =============================================================================
;; Fetch License
;; =============================================================================


;; retrieves the `active_license` for a member.
(reg-event-fx
 :member.license/fetch
 (fn [_ [k account-id]]
   (if (nil? account-id)
     (do
       (timbre/error "No account id provided to `:member.license/fetch`")
       {:dispatch [:notify/failure "Whoops! Something went wrong."]})
     {:dispatch [:ui/loading k true]
      :graphql  {:query      [[:account {:id account-id}
                               [[:deposit [:id :due :amount :amount_remaining :amount_paid :amount_pending :status]]
                                [:active_license
                                 [:id :rate :starts :ends :status :term
                                  [:unit [:id :number]]
                                  [:property [:id :name :code :cover_image_url]]
                                  [:payments [:id :description :type :amount :late_fee_due
                                              :late_fee_paid :status :due :paid_on :pstart :pend]]
                                  [:transition [:id :type :date]]]]]]]
                 :on-success [:member.fetch.license/success k]
                 :on-failure [:graphql/failure k]}})))


(reg-event-fx
 :member.fetch.license/success
 [(path db/path)]
 (fn [{:keys [db]} [_ k response]]
   (let [{:keys [active_license deposit]} (get-in response [:data :account])]
     {:db       (assoc db :license active_license :deposit deposit)
      :dispatch [:ui/loading k false]})))


;; =============================================================================
;; Pay Rent Payment
;; =============================================================================


(reg-event-fx
 :member/pay-rent-payment!
 (fn [_ [k payment-id source-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation   [[:pay_rent_payment {:id     payment-id
                                                :source source-id}
                             [:id]]]
               :on-success [::make-payment-success k payment-id]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::make-payment-success
 (fn [{db :db} [_ k modal-id _]]
   (let [account-id (get-in db [:account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:modal/hide modal-id]
                   [:member.license/fetch account-id]
                   [:iface.components.notifications/clear :rent-due]]})))


;; =============================================================================
;; Pay Remainder of Deposit
;; =============================================================================


(reg-event-fx
 :member/pay-deposit!
 (fn [_ [k deposit-id source-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation   [[:pay_remainder_deposit {:source source-id} [:id]]]
               :on-success [::pay-deposit-success k deposit-id]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::pay-deposit-success
 (fn [{db :db} [_ k modal-id _]]
   (let [account-id (get-in db [:account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:modal/hide modal-id]
                   [:member.license/fetch account-id]
                   [:iface.components.notifications/clear :deposit-overdue]]})))



;; ==============================================================================
;; pay payment ==================================================================
;; ==============================================================================


;; TODO: Make more generic
(reg-event-fx
 :member.payment/pay!
 (fn [_ [k payment-id source-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation   [[:payment_pay {:id payment-id :source source-id}
                             [:id]]]
               :on-success [::pay-success k payment-id]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::pay-success
 (fn [{db :db} [_ k modal-id _]]
   (let [account-id (get-in db [:account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:modal/hide modal-id]
                   [:member.license/fetch account-id]
                   [:payments/fetch account-id]]})))
