#!/bin/sh
# The generator reads the sources with the same code as the app. After changing any of these
# files in the app (cyprus-pharmacies-android), copy them here with this script.
# Usage: ./sync-from-app.sh /path/to/cyprus-pharmacies-android
set -e
APP="${1:?path to the app repository}"
for f in Models Geo Text Spreadsheet MinistrySource Sources Feed; do
  cp "$APP/app/src/main/java/cy/pharmacies/app/data/$f.kt" "$(dirname "$0")/src/main/kotlin/cy/pharmacies/app/data/"
done
echo "Copied. Build with ./gradlew build and commit."
