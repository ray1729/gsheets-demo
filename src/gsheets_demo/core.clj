(ns gsheets-demo.core
  (:require [clojure.java.io :as io])
  (:import com.google.gdata.client.spreadsheet.SpreadsheetService
           com.google.gdata.data.spreadsheet.SpreadsheetFeed
           com.google.gdata.data.spreadsheet.WorksheetFeed
           com.google.gdata.data.spreadsheet.CellFeed
           com.google.api.client.googleapis.auth.oauth2.GoogleCredential
           com.google.api.client.json.jackson2.JacksonFactory
           com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
           java.net.URL
           java.util.Collections))

(def application-name "gsheetdemo-v0.0.1")

(def credentials-resource (io/resource "GSheetDemo-041db3d758a1.json"))

(def oauth-scope "https://spreadsheets.google.com/feeds")

(def spreadsheet-feed-url (URL. "https://spreadsheets.google.com/feeds/spreadsheets/private/full"))

(defn get-credential
  []
  (with-open [in (io/input-stream credentials-resource)]
    (let [credential (GoogleCredential/fromStream in)]
      (.createScoped credential (Collections/singleton oauth-scope)))))

(defn init-service
  []
  (let [credential (get-credential)
        service (SpreadsheetService. application-name)]
    (.setOAuth2Credentials service credential)
    service))

(defn list-spreadsheets
  [service]
  (.getEntries (.getFeed service spreadsheet-feed-url SpreadsheetFeed)))

(defn find-spreadsheet-by-title
  [service title]
  (let [spreadsheets (filter (fn [sheet] (= (.getPlainText (.getTitle sheet)) title))
                             (list-spreadsheets service))]
    (if (= (count spreadsheets) 1)
      (first spreadsheets)
      (throw (Exception. (format "Found %d spreadsheets with name %s"
                                 (count spreadsheets)
                                 title))))))

(defn list-worksheets
  [service spreadsheet]
  (.getEntries (.getFeed service (.getWorksheetFeedUrl spreadsheet) WorksheetFeed)))

(defn find-worksheet-by-title
  [service spreadsheet title]
  (let [worksheets (filter (fn [ws] (= (.getPlainText (.getTitle ws)) title))
                           (list-worksheets service spreadsheet))]
    (if (= (count worksheets) 1)
      (first worksheets)
      (throw (Exception. (format "Found %d worksheets in %s with name %s"
                                 (count worksheets)
                                 spreadsheet
                                 title))))))

(defn get-cells
  [service worksheet]
  (map (memfn getCell) (.getEntries (.getFeed service (.getCellFeedUrl worksheet) CellFeed))))

(defn to-nested-vec
  [cells]
  (mapv (partial mapv (memfn getValue)) (partition-by (memfn getRow) cells)))

(defn fetch-worksheet
  [service {spreadsheet-title :spreadsheet worksheet-title :worksheet}]
  (if-let [spreadsheet (find-spreadsheet-by-title service spreadsheet-title)]
    (if-let [worksheet (find-worksheet-by-title service spreadsheet worksheet-title)]
      (to-nested-vec (get-cells service worksheet))
      (throw (Exception. (format "Spreadsheet '%s' has no worksheet '%s'"
                                 spreadsheet-title worksheet-title))))
    (throw (Exception. (format "Spreadsheet '%s' not found" spreadsheet-title)))))
