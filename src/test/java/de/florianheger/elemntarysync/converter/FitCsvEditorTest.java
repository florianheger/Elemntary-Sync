package de.florianheger.elemntarysync.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Lines follow the format of real Wahoo ELEMNT files, with made-up serial numbers. */
class FitCsvEditorTest {

    private static final String HEADER = "﻿Type,Local Number,Message,Field 1,Value 1,Units 1,Field 2,Value 2,Units 2,";
    private static final String FILE_ID_DEFINITION =
            "Definition,0,file_id,serial_number,1,,time_created,1,,manufacturer,1,,product,1,,type,1,,";
    private static final String FILE_ID =
            "Data,0,file_id,serial_number,\"1111111111\",,time_created,\"1100000000\",,manufacturer,\"32\",,product,\"63\",,type,\"4\",,";
    private static final String MAIN_DEVICE =
            "Data,0,device_info,timestamp,\"1100000000\",s,serial_number,\"1111111111\",,product_name,\"ELEMNT ROAM\",,"
                    + "manufacturer,\"32\",,product,\"63\",,device_index,\"0\",,hardware_version,\"5\",,serial_number,\"99999999999\",null";
    private static final String RADAR =
            "Data,6,device_info,timestamp,\"1100000000\",s,serial_number,\"2222222222\",,product_name,\"Radar\",,"
                    + "manufacturer,\"1\",,garmin_product,\"295\",,device_index,\"5\",,serial_number,\"2222222222\",null";
    private static final String POWER_METER =
            "Data,6,device_info,timestamp,\"1100000000\",s,serial_number,\"3333333333\",,product_name,\"Leistungsmesser\",,"
                    + "product,\"7\",,device_index,\"4\",,serial_number,\"3333333333\",null";
    private static final String HEART_RATE =
            "Data,6,device_info,timestamp,\"1100000000\",s,serial_number,\"10032\",,product_name,\"Puls\",,"
                    + "device_index,\"6\",,ble_device_type,\"120\",,source_type,\"3\",,serial_number,\"1E00FB32\",null";

    @Test
    void convertsWahooFileToGarmin() {
        FitCsvEditor.EditResult result = FitCsvEditor.edit(
                List.of(HEADER, FILE_ID_DEFINITION, FILE_ID, MAIN_DEVICE, RADAR, POWER_METER, HEART_RATE, MAIN_DEVICE));

        assertEquals(List.of(
                HEADER,
                FILE_ID_DEFINITION,
                FILE_ID.replace("manufacturer,\"32\"", "manufacturer,\"1\"").replace("product,\"63\"", "product,\"3121\""),
                MAIN_DEVICE.replace("manufacturer,\"32\"", "manufacturer,\"1\"").replace("product,\"63\"", "product,\"3121\""),
                RADAR,
                POWER_METER,
                HEART_RATE.replace("serial_number,\"1E00FB32\",null", "serial_number,,null"),
                MAIN_DEVICE.replace("manufacturer,\"32\"", "manufacturer,\"1\"").replace("product,\"63\"", "product,\"3121\"")),
                result.lines());
        assertEquals(2, result.deviceLines());
        assertEquals(1, result.fileIdLines());
        assertEquals(1, result.serialLines());
    }

    @Test
    void handlesGarminProductFieldName() {
        String mainDevice = MAIN_DEVICE.replace(",product,", ",garmin_product,");
        String fileId = FILE_ID.replace(",product,", ",garmin_product,");

        FitCsvEditor.EditResult result = FitCsvEditor.edit(List.of(fileId, mainDevice));

        assertEquals(List.of(
                fileId.replace("manufacturer,\"32\"", "manufacturer,\"1\"").replace("garmin_product,\"63\"", "garmin_product,\"3121\""),
                mainDevice.replace("manufacturer,\"32\"", "manufacturer,\"1\"").replace("garmin_product,\"63\"", "garmin_product,\"3121\"")),
                result.lines());
    }

    @Test
    void onlyChangesMainDeviceWithWahooValues() {
        String otherDevice = MAIN_DEVICE.replace("device_index,\"0\"", "device_index,\"9\"");

        FitCsvEditor.EditResult result = FitCsvEditor.edit(List.of(FILE_ID, otherDevice));

        assertEquals(otherDevice, result.lines().get(1));
        assertEquals(0, result.deviceLines());
    }

    @Test
    void alreadyConvertedFileIsUnchanged() {
        List<String> converted = FitCsvEditor.edit(List.of(HEADER, FILE_ID, MAIN_DEVICE, HEART_RATE)).lines();

        FitCsvEditor.EditResult again = FitCsvEditor.edit(converted);

        assertEquals(converted, again.lines());
        assertEquals(0, again.deviceLines() + again.fileIdLines() + again.serialLines());
    }

    @Test
    void definitionLinesAreNeverChanged() {
        String definition = "Definition,6,device_info,timestamp,1,,serial_number,\"1E00FB32\",,manufacturer,\"32\",,device_index,\"0\",,";

        FitCsvEditor.EditResult result = FitCsvEditor.edit(List.of(FILE_ID, definition));

        assertEquals(definition, result.lines().get(1));
    }

    @Test
    void missingFileIdFails() {
        assertThrows(IllegalStateException.class, () -> FitCsvEditor.edit(List.of(HEADER, MAIN_DEVICE)));
    }
}
