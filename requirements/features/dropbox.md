# Feature: Dropbox
This file contains all the functionality with the Dropbox.

## Requirements:
1. Listen to changes in the folder "Apps/WahooFitness". Changes should be detected after a maximum of one minute.
2. Download the file and call the function "ProcessFitFile(file)" with the .fit file that was downloaded.
3. If processing succeeded, move the file in the Dropbox to the subfolder "Apps/WahooFitness/Processed". If it failed, move it to "Apps/WahooFitness/Failed".
4. Every .fit file directly in "Apps/WahooFitness" counts as unprocessed. On startup, all of them are processed, including files that were there before the first start.
