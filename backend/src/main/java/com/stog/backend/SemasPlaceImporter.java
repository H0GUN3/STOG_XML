package com.stog.backend;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Source-specific, bounded SEMAS CSV importer for the existing V6 common Place table. */
final class SemasPlaceImporter {
    static final String SOURCE_TYPE = "SEMAS_STORE";
    static final int BATCH_SIZE = 500;
    static final int FIELD_LIMIT = 8_192;
    static final int RECORD_LIMIT = 32_768;
    static final List<String> HEADER = List.of(
        "상가업소번호","상호명","지점명","상권업종대분류코드","상권업종대분류명","상권업종중분류코드","상권업종중분류명",
        "상권업종소분류코드","상권업종소분류명","표준산업분류코드","표준산업분류명","시도코드","시도명","시군구코드",
        "시군구명","행정동코드","행정동명","법정동코드","법정동명","지번코드","대지구분코드","대지구분명","지번본번지",
        "지번부번지","지번주소","도로명코드","도로명","건물본번지","건물부번지","건물관리번호","건물명","도로명주소",
        "구우편번호","신우편번호","동정보","층정보","호정보","경도","위도");

    ImportReport importCsv(Connection connection, Reader input, String entryName, int maximumRows) throws ImportFailure {
        if (maximumRows < 0) throw new IllegalArgumentException("maximumRows must be nonnegative");
        boolean originalAutoCommit;
        try { originalAutoCommit = connection.getAutoCommit(); connection.setAutoCommit(false); }
        catch (SQLException exception) { throw new ImportFailure("DATABASE", 0, exception); }
        int accepted = 0;
        try {
            createStage(connection);
            CsvReader csv = new CsvReader(input);
            List<String> header = csv.next();
            if (header == null) throw new InvalidRow("EMPTY_FILE", 1);
            if (!header.isEmpty() && header.get(0).startsWith("\ufeff")) header.set(0, header.get(0).substring(1));
            if (!HEADER.equals(header)) throw new InvalidRow("INVALID_HEADER", 1);
            try (PreparedStatement stage = connection.prepareStatement("INSERT INTO semas_place_stage(stage_id,source_key,name,normalized_name,category,normalized_category,region_label,normalized_region_label,address,road_address,latitude,longitude,branch_nonblank,category_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int pending = 0;
                while (maximumRows == 0 || accepted < maximumRows) {
                    List<String> row = csv.next();
                    if (row == null) break;
                    int rowNumber = accepted + 2;
                    Mapped mapped = map(row, rowNumber);
                    bind(stage, mapped); stage.addBatch(); pending++; accepted++;
                    if (pending == BATCH_SIZE) { stage.executeBatch(); pending = 0; }
                }
                if (pending > 0) stage.executeBatch();
            }
            long existing = scalar(connection, "SELECT count(*) FROM semas_place_stage s JOIN place p ON p.source_type='" + SOURCE_TYPE + "' AND p.source_key=s.source_key");
            long branchRows = scalar(connection, "SELECT count(*) FROM semas_place_stage WHERE branch_nonblank");
            long categoryCodes = scalar(connection, "SELECT count(DISTINCT category_code) FROM semas_place_stage");
            long reviewGroups = scalar(connection, "SELECT count(*) FROM (SELECT normalized_name,COALESCE(address,''),COALESCE(road_address,''),latitude,longitude FROM semas_place_stage GROUP BY 1,2,3,4,5 HAVING count(*)>1) d");
            int affected;
            try (Statement statement = connection.createStatement()) {
                affected = statement.executeUpdate("INSERT INTO place(id,source_type,source_key,name,normalized_name,category,normalized_category,region_label,normalized_region_label,address,road_address,description,latitude,longitude) SELECT stage_id,'" + SOURCE_TYPE + "',source_key,name,normalized_name,category,normalized_category,region_label,normalized_region_label,address,road_address,NULL,latitude,longitude FROM semas_place_stage ON CONFLICT (source_type,source_key) WHERE source_type IS NOT NULL AND source_key IS NOT NULL DO UPDATE SET name=EXCLUDED.name,normalized_name=EXCLUDED.normalized_name,category=EXCLUDED.category,normalized_category=EXCLUDED.normalized_category,region_label=EXCLUDED.region_label,normalized_region_label=EXCLUDED.normalized_region_label,address=EXCLUDED.address,road_address=EXCLUDED.road_address,description=NULL,latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude");
            }
            if (affected != accepted) throw new SQLException("Unexpected upsert count");
            connection.commit();
            return new ImportReport(entryName, accepted, accepted - existing, existing, branchRows, categoryCodes, reviewGroups);
        } catch (InvalidRow exception) {
            rollback(connection); throw new ImportFailure(exception.reason, exception.rowNumber, exception);
        } catch (IOException exception) {
            rollback(connection); throw new ImportFailure("INVALID_CSV", accepted + 2, exception);
        } catch (SQLException exception) {
            rollback(connection);
            String reason = "DATABASE";
            if ("23505".equals(exception.getSQLState())) reason = "DUPLICATE_SOURCE_KEY";
            throw new ImportFailure(reason, accepted + 1, exception);
        } finally {
            try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) { }
        }
    }

    private static void createStage(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE semas_place_stage(stage_id uuid NOT NULL,source_key varchar(200) PRIMARY KEY,name varchar(200) NOT NULL,normalized_name varchar(200) NOT NULL,category varchar(100) NOT NULL,normalized_category varchar(100) NOT NULL,region_label varchar(100) NOT NULL,normalized_region_label varchar(100) NOT NULL,address varchar(300),road_address varchar(300),latitude numeric(9,6) NOT NULL,longitude numeric(10,6) NOT NULL,branch_nonblank boolean NOT NULL,category_code varchar(100) NOT NULL) ON COMMIT DROP");
        }
    }

    private static Mapped map(List<String> row, int rowNumber) throws InvalidRow {
        if (row.size() != HEADER.size()) throw new InvalidRow("INVALID_COLUMN_COUNT", rowNumber);
        String sourceKey = required(row.get(0), 200, "INVALID_SOURCE_KEY", rowNumber);
        String name = required(row.get(1), 200, "INVALID_NAME", rowNumber);
        String branch = optional(row.get(2), 200, "INVALID_BRANCH", rowNumber);
        String categoryCode = required(row.get(7), 100, "INVALID_CATEGORY_CODE", rowNumber);
        String category = required(row.get(8), 100, "INVALID_CATEGORY", rowNumber);
        String region = required(row.get(12), 100, "INVALID_REGION", rowNumber);
        String address = optional(row.get(24), 300, "INVALID_ADDRESS", rowNumber);
        String road = optional(row.get(31), 300, "INVALID_ROAD_ADDRESS", rowNumber);
        BigDecimal longitude = coordinate(row.get(37), -180, 180, "INVALID_LONGITUDE", rowNumber);
        BigDecimal latitude = coordinate(row.get(38), -90, 90, "INVALID_LATITUDE", rowNumber);
        return new Mapped(UUID.randomUUID(), sourceKey, name, searchKey(name), category, searchKey(category), region,
            searchKey(region), address, road, latitude, longitude, branch != null, categoryCode);
    }

    private static String required(String raw, int maximum, String reason, int row) throws InvalidRow {
        String value = display(raw, maximum, reason, row); if (value == null) throw new InvalidRow(reason, row); return value;
    }
    private static String optional(String raw, int maximum, String reason, int row) throws InvalidRow {
        String value = trimUnicode(Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC));
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > maximum || value.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRow(reason, row);
        return value;
    }
    private static String display(String raw, int maximum, String reason, int row) throws InvalidRow { return optional(raw, maximum, reason, row); }
    private static String trimUnicode(String value) {
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    private static String searchKey(String display) {
        String lower = display.toLowerCase(Locale.ROOT); StringBuilder result = new StringBuilder(lower.length()); boolean spaced = false;
        for (int offset = 0; offset < lower.length();) { int cp = lower.codePointAt(offset); offset += Character.charCount(cp); if (whitespace(cp)) spaced = true; else { if (spaced && result.length() > 0) result.append(' '); result.appendCodePoint(cp); spaced = false; } }
        return result.toString();
    }
    private static boolean whitespace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
    private static BigDecimal coordinate(String raw, int minimum, int maximum, String reason, int row) throws InvalidRow {
        String value = trimUnicode(raw == null ? "" : raw); if (value.isEmpty()) throw new InvalidRow(reason, row);
        try { BigDecimal decimal = new BigDecimal(value); if (decimal.compareTo(BigDecimal.valueOf(minimum)) < 0 || decimal.compareTo(BigDecimal.valueOf(maximum)) > 0) throw new InvalidRow(reason, row); return decimal.setScale(6, RoundingMode.HALF_UP); }
        catch (NumberFormatException exception) { throw new InvalidRow(reason, row); }
    }
    private static void bind(PreparedStatement statement, Mapped row) throws SQLException {
        statement.setObject(1,row.id);statement.setString(2,row.sourceKey);statement.setString(3,row.name);statement.setString(4,row.normalizedName);statement.setString(5,row.category);statement.setString(6,row.normalizedCategory);statement.setString(7,row.region);statement.setString(8,row.normalizedRegion);statement.setString(9,row.address);statement.setString(10,row.roadAddress);statement.setBigDecimal(11,row.latitude);statement.setBigDecimal(12,row.longitude);statement.setBoolean(13,row.branchNonblank);statement.setString(14,row.categoryCode);
    }
    private static long scalar(Connection connection, String sql) throws SQLException { try (Statement statement=connection.createStatement(); ResultSet result=statement.executeQuery(sql)) { result.next(); return result.getLong(1); } }
    private static void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }

    record ImportReport(String entryName,long rows,long inserted,long updated,long nonblankBranches,long distinctCategoryCodes,long reviewDuplicateGroups) {}
    static final class ImportFailure extends Exception {
        private final String reason; private final int rowNumber;
        ImportFailure(String reason,int rowNumber,Throwable cause){super("SEMAS import failed: "+reason,cause);this.reason=reason;this.rowNumber=rowNumber;}
        String reason(){return reason;} int rowNumber(){return rowNumber;}
    }
    private record Mapped(UUID id,String sourceKey,String name,String normalizedName,String category,String normalizedCategory,String region,String normalizedRegion,String address,String roadAddress,BigDecimal latitude,BigDecimal longitude,boolean branchNonblank,String categoryCode) {}
    private static final class InvalidRow extends Exception { final String reason; final int rowNumber; InvalidRow(String reason,int rowNumber){this.reason=reason;this.rowNumber=rowNumber;} }

    static final class CsvReader {
        private final PushbackReader reader;
        CsvReader(Reader reader){this.reader=new PushbackReader(reader,1);}
        List<String> next() throws IOException {
            ArrayList<String> fields=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,afterQuote=false,started=false;int record=0;
            while(true){int read=reader.read();if(read<0){if(!started&&fields.isEmpty()&&field.isEmpty())return null;if(quoted)throw new IOException("Unterminated quoted field");fields.add(field.toString());return fields;}char c=(char)read;started=true;record++;if(record>RECORD_LIMIT)throw new IOException("Record too large");
                if(quoted){if(c=='"'){int next=reader.read();if(next=='"'){field.append('"');record++;}else{quoted=false;afterQuote=true;if(next>=0)reader.unread(next);}}else{field.append(c=='\r'?normalizeCr():c);checkField(field);}continue;}
                if(afterQuote){if(c==','){fields.add(field.toString());field.setLength(0);afterQuote=false;continue;}if(c=='\n'||c=='\r'){if(c=='\r')consumeLf();fields.add(field.toString());return fields;}throw new IOException("Unexpected character after quote");}
                if(c=='"'){if(field.length()!=0)throw new IOException("Quote inside unquoted field");quoted=true;continue;}
                if(c==','){fields.add(field.toString());field.setLength(0);continue;}
                if(c=='\n'||c=='\r'){if(c=='\r')consumeLf();fields.add(field.toString());return fields;}
                field.append(c);checkField(field);
            }
        }
        private char normalizeCr() throws IOException { int next=reader.read();if(next!='\n'&&next>=0)reader.unread(next);return '\n'; }
        private void consumeLf() throws IOException { int next=reader.read();if(next!='\n'&&next>=0)reader.unread(next); }
        private static void checkField(StringBuilder field) throws IOException { if(field.length()>FIELD_LIMIT)throw new IOException("Field too large"); }
    }
}
