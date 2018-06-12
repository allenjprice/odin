(ns apply.subs
  (:require [re-frame.core :refer [reg-sub]]
            [toolbelt.core :as tb]))


;; l10n =========================================================================


(reg-sub
 :language
 (fn [db _]
   (get-in db [:lang])))


;; route ========================================================================


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


;; user =========================================================================


(reg-sub
 :user
 (fn [db _]
   (:account db)))
