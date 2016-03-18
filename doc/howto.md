# Reading Google Sheets from Clojure

One of the user stories I had to tackle in a recent sprint was to import data maintained by a non-technical staff member in a Google Spreadsheet into our analytics database. I quickly found a [Java API for Google Spreadsheets](https://developers.google.com/google-apps/spreadsheets/) that looked promising but turned out to be more tricky to get up and running than at first glance. In this article, I show you how to use this library from Clojure and avoid some of the pitfalls I fell into.

## Google Spreadsheets API

The [GData Java client](https://github.com/google/gdata-java-client) referenced in the [Google Spreadsheets API documentation](https://developers.google.com/google-apps/spreadsheets/) uses an old XML-based protocol, which is mostly deprecated. We are recommended to use the newer, [JSON-based client](https://github.com/google/google-api-java-client). After chasing my tail on this, I discovered that Google Spreadsheets does not yet support this new API and we *do* need the GData client after all.

## The first hurdle: dependencies

The GData Java client is not available from Maven, so we have to [download a zip archive](http://storage.googleapis.com/gdata-java-client-binaries/gdata-src.java-1.47.1.zip). The easiest way to use these from a Leiningen project is to use `mvn` to install the required jar files in our local repository and specify the dependencies in the usual way. This handy script automates the process, only downloading the archive if necessary. (For this project, we only need the `gdata-core` and `gdata-spreadsheet` jars, but the script is easily extended if you need other components.)

    #!/bin/bash

    set -e

    function log () {
        echo "$1" >&2
    }

    function install_artifact () {
        log "Installing artifact $2"
        mvn install:install-file -DgroupId="$1" -DartifactId="$2" -Dversion="$3" -Dfile="$4" \
            -Dpackaging=jar -DgeneratePom=true
    }

    R="${HOME}/.m2/repository"
    V="1.47.1"
    U="http://storage.googleapis.com/gdata-java-client-binaries/gdata-src.java-${V}.zip"

    if test -r "${R}/com/google/gdata/gdata-core/1.0/gdata-core-1.0.jar" \
            -a -r "${R}/com/google/gdata/gdata-spreadsheet/3.0/gdata-spreadsheet-3.0.jar";
    then
        log "Artifacts up-to-date"
        exit 0
    fi

    log "Downloading $U"
    cd $(mktemp -d)
    wget "${U}"
    unzip "gdata-src.java-${V}.zip"

    install_artifact com.google.gdata gdata-core 1.0 gdata/java/lib/gdata-core-1.0.jar

    install_artifact com.google.gdata gdata-spreadsheet 3.0 gdata/java/lib/gdata-spreadsheet-3.0.jar

Once we've installed these jars, we can configure dependencies as follows:

    (defproject gsheets-demo "0.1.0-SNAPSHOT"
      :description "Google Sheets Demo"
      :url "https://github.com/ray1729/gsheets-demo"
      :license {:name "Eclipse Public License"
                :url "http://www.eclipse.org/legal/epl-v10.html"}
      :dependencies [[org.clojure/clojure "1.8.0"]
                     [com.google.gdata/gdata-core "1.0"]
                     [com.google.gdata/gdata-spreadsheet "3.0"]])

## The second hurdle: authentication

This is a pain, as the documentation for the GData Java client is incomplete and at times confusing, and the examples it ships with no longer work as they use a deprecated OAuth version. The example Java code in the documentation tells us:

``` Java
// TODO: Authorize the service object for a specific user (see other sections)
```

The other sections were no more enlightening, but after more digging and reading of source code, I realised we can use the `google-api-client` to manage our OAuth credentials and simply pass that credentials object to the GData client. This library is already available from a central Maven repository, so we can simply update our project's dependencies to pull it in:

    :dependencies [[org.clojure/clojure "1.8.0"]
                   [com.google.api-client/google-api-client "1.21.0"]
                   [com.google.gdata/gdata-core "1.0"]
                   [com.google.gdata/gdata-spreadsheet "3.0"]]

## OAuth credentials

Before we can start using OAuth, we have to register our client with Google. This is done via the
[Google Developers Console](https://console.developers.google.com/). See [Using OAuth 2.0 to Access Google APIs](https://developers.google.com/identity/protocols/OAuth2) for full details, but here's a quick-start guide to creating credentials for a service account.

Navigate to the [Developers Console](https://console.developers.google.com/). Click on *Enable and manage APIs* and select *Create a new project*. Enter the project name and click *Create*.

Once project is created, click on *Credentials* in the sidebar, then the *Create Credentials* drop-down. As our client is going to run from cron, we want to enable server-to-server authentication, so select *Service account key*. On the next screen, select *New service account* and enter a name. Make sure the *JSON* radio button is selected, then click on *Create*.

Copy the downloaded JSON file into your project's `resources` directory. It should look something like:

    {
      "type": "service_account",
      "project_id": "gsheetdemo",
      "private_key_id": "041db3d758a1a7ef94c9c59fb3bccd2fcca41eb8",
      "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
      "client_email": "gsheets-demo@gsheetdemo.iam.gserviceaccount.com",
      "client_id": "106215031907469115769",
      "auth_uri": "https://accounts.google.com/o/oauth2/auth",
      "token_uri": "https://accounts.google.com/o/oauth2/token",
      "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
      "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/gsheets-demo%40gsheetdemo.iam.gserviceaccount.com"
    }

We'll use this in a moment to create a `GoogleCredential` object, but before that navigate to Google Sheets and create a test spreadsheet. Grant read access to the spreadsheet to the email address found in `client_email` in your downloaded credentials.

## A simple Google Spreadsheets client

We're going to be using a Java client, so it should come as no surprise that our namespace imports a lot of Java classes:

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

We start by defining some constants for our application. The crenentials resource is the JSON file we downloaded from the developer console:

    (def application-name "gsheetdemo-v0.0.1")

    (def credentials-resource (io/resource "GSheetDemo-041db3d758a1.json"))

    (def oauth-scope "https://spreadsheets.google.com/feeds")

    (def spreadsheet-feed-url (URL. "https://spreadsheets.google.com/feeds/spreadsheets/private/full"))

With this in hand, we can create a `GoogleCredential` object and initialize the Google Sheets service:

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

Let's try it at a REPL:

    lein repl

    user=> (require '[gsheets-demo.core :as gsheets])
    nil
    user=> (def service (gsheets/init-service))
    #'user/service
    user=> (.getEntries (.getFeed service
                                  gsheets/spreadsheet-feed-url
                                  com.google.gdata.data.spreadsheet.SpreadsheetFeed))
    (#object[com.google.gdata.data.spreadsheet.SpreadsheetEntry 0x43ab2a3e "com.google.gdata.data.spreadsheet.SpreadsheetEntry@43ab2a3e"])

Great! We can see the one spreadsheet we granted our service account read access. Let's wrap this up in a function and implemnet a helper to find a spreadsheet by name:

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

Back at the REPL:

    user=> (def spreadsheet (gsheets/find-spreadsheet-by-title service "Colour Counts"))
    user=>  (.getPlainText (.getTitle spreadsheet))
    "Colour Counts"

A spreadsheet contains one or more worksheets, so the next functions we implement take a `SpreadsheetEntry` object and list or search worksheets:

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

...and at the REPL:

    user=> (def worksheets (gsheets/list-worksheets service spreadsheet))
    user=> (map (fn [ws] (.getPlainText (.getTitle ws))) worksheets)
    ("Sheet1")

Our next function returns the cells belonging to a worksheet:

    (defn get-cells
      [service worksheet]
      (map (memfn getCell) (.getEntries (.getFeed service (.getCellFeedUrl worksheet) CellFeed))))

This gives us a flat list of `Cell` objects. It will be much more convenient to work in Clojure with a nested vector of the cell values:

    (defn to-nested-vec
      [cells]
      (mapv (partial mapv (memfn getValue)) (partition-by (memfn getRow) cells)))

We now have all the building blocks for the function that will be the main entry point to our minimal Clojure API:

    (defn fetch-worksheet
      [service {spreadsheet-title :spreadsheet worksheet-title :worksheet}]
      (if-let [spreadsheet (find-spreadsheet-by-title service spreadsheet-title)]
        (if-let [worksheet (find-worksheet-by-title service spreadsheet worksheet-title)]
          (to-nested-vec (get-cells service worksheet))
          (throw (Exception. (format "Spreadsheet '%s' has no worksheet '%s'"
                                     spreadsheet-title worksheet-title))))
        (throw (Exception. (format "Spreadsheet '%s' not found" spreadsheet-title)))))

With this in hand:

    user=> (def sheet (gsheets/fetch-worksheet service {:spreadsheet "Colour Counts" :worksheet "Sheet1"}))
    #'user/sheet
    user=> (clojure.pprint/pprint sheet)
    [["Colour" "Count"]
     ["red" "123"]
     ["orange" "456"]
     ["yellow" "789"]
     ["green" "101112"]
     ["blue" "131415"]
     ["indigo" "161718"]
     ["violet" "192021"]]
    nil

Our `to-nested-vec` function returns the cell values as strings. I could have used the `getNumericValue` method instead of `getValue`, but then `to-nested-vec` would have to know what data type to expect in each cell. Instead, I used [Plumatic Schema](https://github.com/plumatic/schema) to define a schema for each row, and used its [data coercion](http://plumatic.github.io/schema-0-2-0-back-with-clojurescript-data-coercion/) features to coerce each column to the desired data type - but that's a blog post for another day.

Code for the examples above is available on Github <https://github.com/ray1729/gsheets-demo>. We have barely scratched the surface of the Google Spreadsheets API; check out the [API Documentation](https://developers.google.com/google-apps/spreadsheets/) if you need to extend this code, for example to create or update spreadsheets.
