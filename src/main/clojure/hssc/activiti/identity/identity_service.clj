(ns hssc.activiti.identity.identity-service
  (:use [hssc.util :only [def-bean-maker]])
  (:require [clojure.java.io :as jio]
            [clojure.string :as s])
  (:require [obis-shared.entity.identity :as id])
  (:require [inflections.core :as inf])
  (:use [fogus.unk :only [memo-ttl]])
  (:import org.activiti.engine.ActivitiException
           (org.activiti.engine.identity
            GroupQuery
            UserQuery
            User
            Group
            Picture))
  (:gen-class
    :name hssc.activiti.identity.IdentityServiceImpl
    :implements [org.activiti.engine.IdentityService]))

(def all-users
  (memo-ttl
    (fn []
      (for [attributes (id/all-identities),
            :when (contains? attributes :activiti_groups)]
        {:first-name (attributes :first_name),
         :last-name (attributes :last_name),
         :id (attributes :uid)
         :groups (attributes :activiti_groups)}))
    300000))

; Is it okay to say that a group only exists if it has members? Else we'll have to
; store groups explicitely somewhere...
(defn all-groups
  []
  (for [group-id (distinct (mapcat :groups (all-users)))]
    {:id group-id, :name (inf/camelize group-id), :type "assignment"}))

(defn user-by-id [id] (first (filter #(= id (:id %)) (all-users))))
(defn group-by-id [id] (first (filter #(= id (:id %)) (all-groups))))

(def-bean-maker make-user
  User
  email first-name id last-name password)

(def-bean-maker make-group
  Group
  id name type)

; Cannot use defrecord for either of these as they have to implement
; their own count function
(declare add-group-filter)
(deftype GroupQueryImpl [info]
  GroupQuery
  (groupId [self group-id]
    (add-group-filter self #(= (:id %) group-id)))
  (groupMember [self group-member-user-id]
    (add-group-filter self (comp
                             (set (:groups (user-by-id group-member-user-id)))
                             :id)))
  (groupName [self group-name]
    (add-group-filter self #(= group-name (:name %))))
  ;(groupNameLike [_ group-name-like] _)
  (groupType [self group-type]
    (add-group-filter self #(= group-type (:type %))))
  (orderByGroupId [_]
    (new GroupQueryImpl (assoc info :order :id :post identity)))
  (orderByGroupName [_]
    (new GroupQueryImpl (assoc info :order :name :post identity)))
  (orderByGroupType [_]
    (new GroupQueryImpl (assoc info :order :type :post identity)))
  (asc [self]
    (new GroupQueryImpl (assoc info :post identity)))
  (count [self] (clojure.core/count (.list self)))
  (desc [_]
    (new GroupQueryImpl (assoc info :post reverse)))
  (list [_]
    (->>
      (all-groups)
      (filter (fn [group-map] (every? #(% group-map) (:filters info))))
      ((if-let [sort-by-fn (:order info)] (partial sort-by sort-by-fn) identity))
      ((or (:post info) identity))
      (map make-group)))
  (listPage [self first-result max-results]
    (->>
      self
      .list
      (drop first-result)
      (take max-results)))
  (singleResult [self]
    (let [res (.list self)]
      (cond
        (= 1 (count res))
          (first res)
        (< 1 (count res))
          (throw (new ActivitiException "singleResult called on groupQuery with more than one result!"))))))

(defn- add-group-filter
  [group-query f]
  (new GroupQueryImpl (update-in (.info group-query) [:filters] conj f)))

(declare add-filter)
(deftype UserQueryImpl [info]
  UserQuery
  (memberOfGroup [_ group-id]
    (new UserQueryImpl
         (update-in info :filters conj #(some #{group-id} (:groups %)))))
  (orderByUserEmail [_]
    (new UserQueryImpl (assoc info :order :email :post identity)))
  (orderByUserFirstName [_]
    (new UserQueryImpl (assoc info :order :first-name :post identity)))
  (orderByUserId [_]
    (new UserQueryImpl (assoc info :order :id :post identity)))
  (orderByUserLastName [_]
    (new UserQueryImpl (assoc info :order :last-name :post identity)))
  (userEmail [self email]
    (add-filter self #(= email (:email %))))
  ;(userEmailLike [_ email-like] _)
  (userFirstName [self first-name]
    (add-filter self #(= first-name (:first-name %))))
  ;(userFirstNameLike [_ first-name-like] _)
  (userId [self user-id]
    (add-filter self #(= user-id (:id %))))
  (userLastName [self last-name]
    (add-filter self #(= last-name (:last-name %))))
  ;(userLastNameLike [_ last-name-like] _)
  (asc [_] (new UserQueryImpl (assoc :info :post identity)))
  (count [self] (clojure.core/count (.list self)))
  (desc [_] (new UserQueryImpl (assoc :info :post reverse)))
  (list [_]
    (->>
      (all-users)
      (filter (fn [user-map] (every? #(% user-map) (:filters info))))
      ((if-let [sort-by-fn (:order info)] (partial sort-by sort-by-fn) identity))
      ((or (:post info) identity))
      (map make-user)))
  (listPage [self first-result max-results]
    (->> self
      .list
      (drop first-result)
      (take max-results)))
  (singleResult [self]
    (let [res (.list self)]
      (cond
        (= 1 (count res))
          (first res)
        (< 1 (count res))
          (throw (new ActivitiException "singleResult called on userQuery with more than one result!"))))))

(defn- add-filter
  [user-query f]
  (new UserQueryImpl (update-in (.info user-query) [:filters] conj f)))

; Temporary implementations of the IdentityService methods that
; gives good error information.
(defmacro defsn
  [& names]
  (cons 'do
        (for [name names]
          `(defn ~(symbol (str "-" name)) [& args#]
            (throw (new Exception (str "Stub method called: IdentityService#" '~name (pr-str args#))))))))

(defsn
 checkPassword
;createGroupQuery
 createMembership
;createUserQuery
 deleteGroup
 deleteMembership
 deleteUser
 deleteUserAccount
 deleteUserInfo
 getUserAccount
 getUserAccountNames
;getUserInfo
 getUserInfoKeys
;getUserPicture
 newGroup
 newUser
 saveGroup
 saveUser
 setAuthenticatedUserId
 setUserAccount
 setUserInfo
 setUserPicture)

(defn -getUserInfo
  [_ user-id info-key]
  (get-in (user-by-id user-id) [:info info-key]))

(defn resource-bytes
  "Returns a byte-array."
  [path]
  (let [is (jio/input-stream (jio/resource path))]
    (loop [bs [], next-byte (.read is)]
      (if (neg? next-byte)
        (let [ba (make-array Byte/TYPE (count bs))]
          (doseq [[int i] (map vector bs (range))] (aset ba i (.byteValue int)))
          ba)
        (recur (conj bs next-byte) (.read is))))))


(def fozzie-picture
  (new Picture
    (resource-bytes "org/activiti/explorer/images/fozzie.jpg")
    "image/jpg"))

(defn -createGroupQuery [_] (new GroupQueryImpl {}))
(defn -createUserQuery  [_] (new UserQueryImpl  {}))
(defn -getUserPicture   [_ user-id] fozzie-picture)

(def get-instance (constantly (new hssc.activiti.identity.IdentityServiceImpl)))
