(ns apply.sections.logistics
  (:require [apply.content :as content]
            [antizer.reagent :as ant]
            [re-frame.core :refer [dispatch subscribe]]
            [iface.utils.log :as log]
            [apply.events :as events]
            [apply.db :as db]
            [apply.sections.logistics.pets]))



(defmethod events/save-step-fx :logistics/move-in-date
  [db {:keys [move-in-range] :as params}]
  {:db       (assoc db :logistics/move-in-date  move-in-range)
   :dispatch [:step/advance]})


(defmethod content/view :logistics/move-in-date
  [_]
  [:div
   [:div.w-60-l.w-100
    [:h1 "Let's get started." [:br]
     "When do you want to move-in?"]
    [:p "We'll do our best to accommodate your move-in date, but we cannot
   guarantee that the date you choose will be the date that you move in."]]
   [:div.page-content.w-90-l.w-100
    [ant/button
     {:on-click #(dispatch [:step.current/next {:move-in-range :date}])}
     "select a date"]
    [ant/button
     {:on-click #(dispatch [:step.current/next {:move-in-range :asap}])}
     "asap"]
    [ant/button
     {:on-click #(dispatch [:step.current/next {:move-in-range :flexible}])}
     "i'm flexible"]]])


(defmethod events/save-step-fx :logistics.move-in-date/choose-date
  [db params]
  {:db       (assoc db :logistics.move-in-date/choose-date params)
   :dispatch [:step/advance]})


(defmethod content/view :logistics.move-in-date/choose-date
  [_]
  (log/log "complete?" @(subscribe [:step/complete? :logistics.move-in-date/choose-date]))
  [:div
   [:div.w-60-l.w-100
    [:h1 "Let's get started." [:br]
     "When do you want to move-in?"]
    [:p "We'll do our best to accommodate your move-in date, but we cannot
   guarantee that the date you choose will be the date that you move in."]]
   [:div.page-content.w-90-l.w-100
    [ant/date-picker
     {:on-change #(dispatch [:step.current/next %1])}]]])


;; ==============================================================================
;; logistics/occupancy ==========================================================
;; ==============================================================================


(defmethod events/save-step-fx :logistics/occupancy
  [db params]
  {:db       (assoc db :logistics/occupancy params)
   :dispatch [:step/advance]})


(defmethod db/next-step :logistics/occupancy
  [db]
  (if (= 2 (:logistics/occupancy db))
    :logistics.occupancy/co-occupant
    :logistics/pets))


(defmethod content/view :logistics/occupancy
  [_]
  [:div
   [:div.w-60-l.w-100
    [:h1 "How many adults will be living in the unit?"]
    [:p "Most of our units have one bed and are best suited for one adult, but some are available for two adults."]]
   [:div.page-content.w-90-l.w-100
    [ant/button
     {:on-click #(dispatch [:step.current/next 1])}
     "one"]
    [ant/button
     {:on-click #(dispatch [:step.current/next 2])}
     "two"]]])
