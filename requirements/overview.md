# Elemntary Sync

## General description
It is not possible to directly upload the .fit-files recorded with an Wahoo Elemnt bike computer to Garmin Connect and have all the steps from Garmin Connect like the training effect or buned calories.
This features are available only, if the .fit file was recorded by a Garmin device.
The .fit files containg a device identifier. If we change this to the identifier of a Garmin device and upload the .fit file, we will get all of the stats.

## This project
This project is intended to automatically fetch the .fit files from the Wahoo, change the device identifier and upload it to garmin connect.
THe .fit files are utomatically uploaded to my Dropbox.

The procedure is the following:
1. Listen to any changes in the dropbox. If there is a new file, download the file.
2. Change the neccessary information like the device identifier.
3. Upload the edited file to Garmin Connect.

## Technical information
This project is in Java. It needs to be axecutable in docker and should easy be deployable using Docker compose. A new user needs to create an .ENV file for the authentification to Dropbox and Garmin Connect and thats it.
