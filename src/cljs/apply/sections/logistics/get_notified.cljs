(ns apply.sections.logistics.get-notified
  (:require [apply.content :as content]
            [antizer.reagent :as ant]
            [re-frame.core :refer [dispatch subscribe reg-sub]]
            [apply.events :as events]
            [apply.db :as db]
            [iface.components.ptm.ui.button :as button]
            [apply.routes :as routes]
            [iface.utils.formatters :as format]))


(def step :logistics.move-in-date/get-notified)


;; db ===========================================================================


(defmethod db/has-back-button? step
  [_]
  false)


;; views ========================================================================


(defmethod content/view step
  [_]
  (let [date (subscribe [:step/data :logistics.move-in-date/choose-date])]
    [:div
     [:div.w-60-l.w-100
      [:h1 "We'll send you an email notification before " (format/date-month-day @date) " to remind  you to apply."]
      [:p "Thanks for your interest in Starcity! Hope to see you soon."]]
     [:div.w-80-l.w-100
      [:div.page-content
       [button/primary
        {:on-click #(dispatch [:nav.item/logout])}
        "Close"]]]]))
