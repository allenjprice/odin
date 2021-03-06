(ns apply.sections.personal.background-check-declined
  (:require [apply.content :as content]
            [antizer.reagent :as ant]
            [re-frame.core :refer [dispatch subscribe]]
            [apply.events :as events]
            [apply.db :as db]
            [iface.components.ptm.ui.button :as button]
            [apply.routes :as routes]))


(def step :personal.background-check/declined)


;; db ===========================================================================


(defmethod db/next-step step
  [db]
  nil)


(defmethod db/has-back-button? step
  [_]
  false)


;; views ========================================================================


(defmethod content/view step
  [_]
  [:div
   [:div.w-60-l.w-100
    [:h1 "Thank you for your interest in Starcity."]]
   [:div.w-80-l.w-100
    [:div.page-content
     [button/primary
      {:on-click #(dispatch [:nav.item/logout])}
      "Close"]]]])
