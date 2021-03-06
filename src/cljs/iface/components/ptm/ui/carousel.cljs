(ns iface.components.ptm.ui.carousel
  (:require [cljs.spec.alpha :as s]
            [reagent.core :as r]))


(defn- carousel-next [{on-click :on-click}]
  [:span.chevron.next
   {:on-click #(on-click)}
   [:img {:src "/assets/images/ptm/chevron.svg"}]])


(defn- carousel-back [{on-click :on-click}]
  [:span.chevron.back
   {:on-click #(on-click)}
   [:img {:src "/assets/images/ptm/chevron.svg"}]])


(defn- carousel-dot [{:keys [active]}]
  [:li.dot {:class (when-not active "inactive")}])


(defn- carousel-slide [{:keys [selected img key]}]
  [:li.carousel-slide.carousel-image-large
   {:style {:background-image (str "url('" img "')")}
    :class (when selected
             "active")
    :key   key}])


(defn- idx-next [idx total]
  (if (= idx (- total 1)) 0 (inc idx)))


(defn- idx-back [idx total]
  (if (zero? idx) (dec total) (dec idx)))


(defn carousel
  "Renders the carousel used in the `card` ui component. Has an aspect ratio of 6x4."
  [images]
  (let [index (r/atom 0)]
    (fn [images]
      [:div.card-photo.aspect-ratio--6x4
       {:style {:overflow   "hidden"}}
       [:ul.dots
        (doall
         (map-indexed
          #(with-meta
             [carousel-dot
              {:active (when (= %1 @index) true)}]
             {:key %1})
          images))]
       [carousel-next {:on-click #(swap! index idx-next (count images))}]
       [carousel-back {:on-click #(swap! index idx-back (count images))}]
       [:div.chevron-scrim]
       [:ul
        (doall
         (map-indexed
          (fn [idx image]
            [carousel-slide {:img      image
                             :selected (when (= idx @index) true)
                             :key      idx}])
          images))]])))


(defn modal
  "Renders the carousel used in the `community-modal` ui component. Has an aspect ratio of 16x9."
  [images]
  (let [index (r/atom 0)]
    (fn [images]
      [:div.lightbox-photo.aspect-ratio--16x9
       {:style {:overflow   "hidden"}}
       [:ul.dots
        (doall
         (map-indexed
          #(with-meta
             [carousel-dot
              {:active (when (= %1 @index) true)}]
             {:key %1})
          images))]
       [carousel-next {:on-click #(swap! index idx-next (count images))}]
       [carousel-back {:on-click #(swap! index idx-back (count images))}]
       [:div.chevron-scrim]
       [:ul
        (doall
         (map-indexed
          (fn [idx image]
            [carousel-slide {:img      image
                             :selected (when (= idx @index) true)
                             :key      idx}])
          images))]])))
