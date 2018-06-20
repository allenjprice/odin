(ns apply.core
  (:require [accountant.core :as accountant]
            [antizer.reagent :as ant]
            [apply.content :as content]
            [apply.db :as db]
            [apply.events]
            [apply.routes :as routes]
            [apply.sections.logistics]
            [apply.subs]
            [day8.re-frame.http-fx]
            [goog.dom :as gdom]
            [iface.components.ptm.layout :as layout]
            [iface.modules.graphql :as graphql]
            [iface.utils.routes :as iroutes]
            [reagent.core :as r]
            [re-frame.core :as rf :refer [dispatch subscribe]]
            [iface.components.ptm.icons :as icons]
            [iface.components.ptm.ui :as ui]
            [iface.components.ptm.ui.form :as form]
            [toolbelt.core :as tb]
            [iface.utils.log :as log]))


(defn logout []
  [:div.tr
   [:a {:href "/logout"} "Log Out"]])


(defn- welcome-1 [{name :name} toggle]
  [:div
   [:div.w-60-l.w-100.center
    [:h1.tc "Welcome to Starcity,"]
    [:h2.tc.handwriting name]]
   [:div.page-content.w-90-l.w-100.center.tc
    [:p.tc "You've taken your first step to joining a Starcity community."]
    [:p.tc "We're looking forward to getting to know you."]
    [:br]
    [:p.tc "I'm here to help you with your application. If you have a"]
    [:p.tc "question, click on the " (icons/icon {:type "help" :class "icon-small"}) " icon to send me a message."]
    [:br]
    [:div
     [ant/avatar
      {:icon "user"}]]
    [:h3 "- Mattlio from Chatlio"]
    [:br]
    [:br]

    [ui/button
     {:on-click #(swap! toggle not)
      :type     :secondary
      :class    "mt5"}
     "Let's go!"]]])


(defn- welcome-2 []
  [:section.main.main-no-nav.center
   [:div.w-60-l.w-100.center
    [:h1.tc "Here's what you'll need"]]
   [:div.page-content.w-90-l.w-100.center.tc
    [:div.cf.mt5
     [:div.w-third-l.w-100.fl.ph4
      [:svg
       [:use {:xlinkHref "#truck"}]]
      [:h3 "Logistics"]
      [:p "We’ll need to know stuff like which communities you'd like to join, you’re preferred move-in date, and how long you'll be staying with us."]]
     [:div.w-third-l.w-100.fl.ph4
      [:svg
       [:use {:xlinkHref "#paper"}]]
      [:h3 "Personal Information"]
      [:p "Provide us with some personal information so that we can perform a background check and verify your income."]]
     [:div.w-third-l.w-100.fl.ph4
      [:svg
       [:use {:xlinkHref "#credit-card"}]]
      [:h3 "Application Fee"]
      [:p "It’ll cost $25 for each application – that helps cover the cost of the background check."]]]
    [:button.button.mt5
     {:on-click #(dispatch [:ptm/start])}
     "Got it"]]])


(defmethod content/view :welcome [{:keys [requester] :as route}]
  (let [toggle (r/atom false)]
    (fn []
      [:div
       [:div.w-60-l.w-100.center
        [:svg.logo
         [:use {:xlinkHref "#logomark"}]]]
       (if @toggle
         [welcome-2]
         [welcome-1 requester toggle])
       [:div.bg-top]])))


(defn- nav-item
  [{:keys [label icon section] :as nav-item}]
  (let [is-enabled  (subscribe [:nav.item/enabled? nav-item])
        is-complete (subscribe [:nav.item/complete? nav-item])
        route       (subscribe [:route/current])
        progress    (cond
                      @is-complete                                        :complete
                      (= section (-> @route :params :section-id keyword)) :active
                      :otherwise                                          nil)]
    [layout/nav-item {:progress progress
                      :label    label
                      :icon     icon
                      :disabled (not @is-enabled)
                      :action   (fn [] (dispatch [:nav.item/select nav-item]))}]))


(defn nav []
  (let [nav-items (subscribe [:nav/items])]
    [layout/nav
     {:is-progress? true
      :footer       [layout/nav-footer
                     [layout/nav-item {:label "Help"
                                       :icon  "help"}]
                     [layout/nav-item {:label "Log Out"
                                       :icon  "logout"}]]}
     (doall
      (map-indexed #(with-meta [nav-item %2] {:key %1}) @nav-items))]))


(defn footer []
  (let [has-next      (subscribe [:ui.step.current/has-next?])
        has-prev      (subscribe [:ui.step.current/has-back?])
        next          (subscribe [:step.current/next])
        prev          (subscribe [:step.current/previous])
        next-loading  (subscribe [:ui/loading? :step.current/save])
        next-label    (subscribe [:step.current.next/label])
        next-disabled (subscribe [:step/complete?])]
    [layout/footer
     (tb/assoc-when
      {}
      :primary (when @has-next
                 {:label    @next-label
                  :disabled @next-disabled
                  :loading  @next-loading
                  :action   #(dispatch [:step.current/next])})
      :destructive (when @has-prev
                     {:label "back"
                      :link  (db/step->route @prev)}))]))


(defn welcome-layout []
  (let [screen-two (r/atom false)]
    (fn []
      [layout/layout
       {:pre (list [:div.bg-top {:key 1}]
                   (icons/icon {:type "logomark"}))}
       (if-not @screen-two
         [welcome-1 {:name "Bob Loblaw"} screen-two]
         [welcome-2])])))


(defn layout []
  (let [route (subscribe [:route/current])
        step  (subscribe [:step/current])]
    (if (= (:page @route) :welcome)
      [welcome-layout]
      [layout/layout
       {:nav [nav] :footer [footer]}
       [content/view @route]])))


;; ==============================================================================
;; app entry ====================================================================
;; ==============================================================================


(defn render []
  (r/render
   [ant/locale-provider {:locale (ant/locales "en_US")}
    [layout]]
   (gdom/getElement "apply")))


(defn reload! []
  (render)
  (accountant/dispatch-current!))


(defn ^:export run []
  (let [account (js->clj (aget js/window "account") :keywordize-keys true)]
    (graphql/configure
     "/api/graphql"
     {:on-unauthenticated (fn [_]
                            {:route "/logout"})
      :on-error-fx        (fn [[k _]]
                            {:dispatch [:ui/loading k false]})})


    (rf/dispatch-sync [:app/init account])
    (iroutes/hook-browser-navigation! routes/app-routes)
    (render)))
