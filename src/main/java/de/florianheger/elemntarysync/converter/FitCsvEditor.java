package de.florianheger.elemntarysync.converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the device identifiers in a FitCSVTool CSV so Garmin Connect treats the ride as recorded by a
 * Garmin Edge 530. See requirements/features/fit-processing.csv.
 *
 * <p>Only value cells of {@code Data} lines are changed. The header, {@code Definition} lines and the
 * CSV structure stay untouched. Each field is a {@code name,value,units} triple starting at column 4.
 */
public final class FitCsvEditor {

    static final String GARMIN_MANUFACTURER = "1";
    static final String GARMIN_PRODUCT = "3121";
    private static final String WAHOO_MANUFACTURER = "32";
    private static final String WAHOO_PRODUCT = "63";

    /** The edited lines and how many lines each rule changed. */
    public record EditResult(List<String> lines, int deviceLines, int fileIdLines, int serialLines) {}

    private FitCsvEditor() {
    }

    public static EditResult edit(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        int deviceLines = 0;
        int fileIdLines = 0;
        int serialLines = 0;
        boolean fileIdSeen = false;

        for (String line : lines) {
            if (!line.startsWith("Data,")) {
                result.add(line);
                continue;
            }
            Row row = new Row(line);
            if (row.message().equals("device_info")) {
                if ("0".equals(row.value("device_index")) && makeMainDeviceGarmin(row)) {
                    deviceLines++;
                }
                if (clearNonNumericSerials(row)) {
                    serialLines++;
                }
                if ("0".equals(row.value("device_index")) && WAHOO_MANUFACTURER.equals(row.value("manufacturer"))) {
                    throw new IllegalStateException("device_index 0 still has manufacturer 32: " + line);
                }
            } else if (row.message().equals("file_id")) {
                fileIdSeen = true;
                if (makeFileIdGarmin(row)) {
                    fileIdLines++;
                }
            }
            result.add(row.toLine());
        }

        if (!fileIdSeen) {
            throw new IllegalStateException("No file_id Data line found");
        }
        return new EditResult(result, deviceLines, fileIdLines, serialLines);
    }

    private static boolean makeMainDeviceGarmin(Row row) {
        boolean changed = row.replace("manufacturer", WAHOO_MANUFACTURER, GARMIN_MANUFACTURER);
        changed |= row.replace("product", WAHOO_PRODUCT, GARMIN_PRODUCT);
        changed |= row.replace("garmin_product", WAHOO_PRODUCT, GARMIN_PRODUCT);
        return changed;
    }

    private static boolean makeFileIdGarmin(Row row) {
        if (row.value("manufacturer") == null || (row.value("product") == null && row.value("garmin_product") == null)) {
            throw new IllegalStateException("file_id line has no manufacturer or product field: " + row.toLine());
        }
        boolean changed = row.set("manufacturer", GARMIN_MANUFACTURER);
        changed |= row.set("product", GARMIN_PRODUCT);
        changed |= row.set("garmin_product", GARMIN_PRODUCT);
        return changed;
    }

    private static boolean clearNonNumericSerials(Row row) {
        boolean changed = false;
        for (int i = 0; i < row.fieldCount(); i++) {
            if (row.fieldName(i).equals("serial_number")) {
                String value = row.valueAt(i);
                if (!value.isEmpty() && !value.chars().allMatch(Character::isDigit)) {
                    row.clearAt(i);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** One CSV line split into raw cells, quotes kept, so untouched cells are written back unchanged. */
    private static final class Row {

        private static final int FIRST_FIELD = 3;

        private final List<String> cells;

        Row(String line) {
            cells = split(line);
        }

        String message() {
            return cells.size() > 2 ? cells.get(2) : "";
        }

        int fieldCount() {
            return Math.max(0, (cells.size() - FIRST_FIELD + 2) / 3);
        }

        String fieldName(int field) {
            return cells.get(FIRST_FIELD + 3 * field);
        }

        String valueAt(int field) {
            int index = FIRST_FIELD + 3 * field + 1;
            return index < cells.size() ? unquote(cells.get(index)) : "";
        }

        void clearAt(int field) {
            cells.set(FIRST_FIELD + 3 * field + 1, "");
        }

        /** Value of the first field with this name, or null if the line has no such field. */
        String value(String name) {
            for (int i = 0; i < fieldCount(); i++) {
                if (fieldName(i).equals(name)) {
                    return valueAt(i);
                }
            }
            return null;
        }

        /** Sets every field with this name to the value. Returns whether anything changed. */
        boolean set(String name, String newValue) {
            boolean changed = false;
            for (int i = 0; i < fieldCount(); i++) {
                if (fieldName(i).equals(name) && !valueAt(i).equals(newValue)) {
                    cells.set(FIRST_FIELD + 3 * i + 1, "\"" + newValue + "\"");
                    changed = true;
                }
            }
            return changed;
        }

        /** Sets fields with this name to the new value where they currently hold the old one. */
        boolean replace(String name, String oldValue, String newValue) {
            boolean changed = false;
            for (int i = 0; i < fieldCount(); i++) {
                if (fieldName(i).equals(name) && valueAt(i).equals(oldValue)) {
                    cells.set(FIRST_FIELD + 3 * i + 1, "\"" + newValue + "\"");
                    changed = true;
                }
            }
            return changed;
        }

        String toLine() {
            return String.join(",", cells);
        }

        private static List<String> split(String line) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    continue;
                }
                cell.append(c);
            }
            cells.add(cell.toString());
            return cells;
        }

        private static String unquote(String cell) {
            if (cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\"")) {
                return cell.substring(1, cell.length() - 1).replace("\"\"", "\"");
            }
            return cell;
        }
    }
}
