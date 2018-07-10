(ns apply.subs
  (:require [apply.db :as db]
            [iface.utils.log :as log]
            [re-frame.core :refer [reg-sub]]
            [toolbelt.core :as tb]))


;; ==============================================================================
;; helper =======================================================================
;; ==============================================================================


(reg-sub
 :db
 (fn [db _]
   db))


;; ==============================================================================
;; l10n =========================================================================
;; ==============================================================================


(reg-sub
 :language
 (fn [db _]
   (get-in db [:lang])))


;; ==============================================================================
;; route ========================================================================
;; ==============================================================================


(reg-sub
 :route/current
 (fn [db _]
   (:route db)))


(reg-sub
 :route/path
 :<- [:route/current]
 (fn [{path :path} _]
   path))


(reg-sub
 :route/params
 :<- [:route/current]
 (fn [{params :params} _]
   params))


(reg-sub
 :route/root
 :<- [:route/path]
 (fn [path _]
   (first path)))


;; ==============================================================================
;; user =========================================================================
;; ==============================================================================


(reg-sub
 :user
 (fn [db _]
   (:account db)))


;; ==============================================================================
;; navigation ===================================================================
;; ==============================================================================


(reg-sub
 :nav/items
 (fn [db _]
   (get db db/nav-path)))


(reg-sub
 :nav.item/enabled?
 (fn [db [_ nav-item]]
   (db/can-navigate? db (:section nav-item))))


(reg-sub
 :nav.item/complete?
 (fn [db [_ nav-item]]
   (db/section-complete? db (:section nav-item))))


;; ==============================================================================
;; steps ========================================================================
;; ==============================================================================


(reg-sub
 :step/data
 (fn [db [_ step]]
   (step db)))


(reg-sub
 :step/current
 :<- [:route/current]
 (fn [route _]
   (db/route->step route)))


(reg-sub
 :step/complete?
 :<- [:db]
 :<- [:step/current]
 (fn [[db step] _]
   (db/step-complete? db step)))


(reg-sub
 :ui.step.current/has-back?
 (fn [db _]
   (db/has-back-button? db)))


(reg-sub
 :ui.step.current/has-next?
 (fn [db _]
   (db/has-next-button? db)))


(reg-sub
 :step.current/next
 (fn [db _]
   (db/next-step db)))


(reg-sub
 :step.current/previous
 (fn [db _]
   (db/previous-step db)))


(defn- lbl [s]
  (str "next: " s))


(reg-sub
 :step.current.next/label
 :<- [:step/current]
 (fn [step _]
   (case step
     :logistics.move-in-date/choose-date (lbl "occupancy")
     :logistics.occupancy/co-occupant    (lbl "pets")
     :logistics.pets/dog                 (lbl "communities")
     :logistics.pets/other               (lbl "communities")
     :community/select                   (lbl "term length")
     :personal/phone-number              (lbl "background check")
     :personal.background-check/info     (lbl "income verification")
     :personal/income                    (lbl "about you")
     :personal.income/cosigner           (lbl "about you")
     :personal/about                     (lbl "finish & pay")
     :payment/review                     (lbl "finish & pay")
     "next")))
