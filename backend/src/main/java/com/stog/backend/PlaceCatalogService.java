package com.stog.backend;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
final class PlaceCatalogService {
    private final JdbcTemplate jdbc;
    private final PlaceCursorCodec cursors;

    PlaceCatalogService(JdbcTemplate jdbc, PlaceCursorCodec cursors) {
        this.jdbc = jdbc; this.cursors = cursors;
    }

    Page search(UUID principal, String query, String region, String category, int requestedLimit, String cursorToken) {
        PlaceCursorCodec.Cursor cursor = cursorToken == null ? null
            : cursors.decode(cursorToken, principal, requestedLimit, query, region, category);
        int limit = cursor == null ? requestedLimit : cursor.limit();
        StringBuilder sql = new StringBuilder("SELECT id,name,category,region_label,address,latitude,longitude,normalized_name "
            + "FROM place WHERE name IS NOT NULL AND normalized_name LIKE ? ESCAPE '\\'");
        List<Object> arguments = new ArrayList<>();
        arguments.add(escapeLike(query) + "%");
        if (region != null) { sql.append(" AND normalized_region_label=?"); arguments.add(region); }
        if (category != null) { sql.append(" AND normalized_category=?"); arguments.add(category); }
        if (cursor != null) {
            sql.append(" AND (normalized_name>? OR (normalized_name=? AND id>?))");
            arguments.add(cursor.lastName()); arguments.add(cursor.lastName()); arguments.add(cursor.lastPlaceId());
        }
        sql.append(" ORDER BY normalized_name ASC,id ASC LIMIT ?"); arguments.add(limit + 1);
        List<Row> rows = jdbc.query(sql.toString(), PlaceCatalogService::mapRow, arguments.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        String next = null;
        if (hasMore) {
            Row last = rows.get(rows.size()-1);
            next = cursors.encode(principal, limit, query, region, category, last.normalizedName(), last.place().placeId());
        }
        return new Page(rows.stream().map(Row::place).toList(), next);
    }

    Place detail(UUID placeId) {
        List<Place> rows = jdbc.query("SELECT id,name,category,region_label,address,road_address,description,latitude,longitude "
                + "FROM place WHERE id=? AND name IS NOT NULL AND normalized_name IS NOT NULL",
            PlaceCatalogService::mapDetail, placeId);
        if (rows.isEmpty()) throw new PlaceCatalogException(404,"PLACE_NOT_FOUND","The place was not found");
        return rows.get(0);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
    private static Row mapRow(ResultSet rs,int ignored)throws SQLException {
        Place place=new Place(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
            null,null,rs.getBigDecimal(6),rs.getBigDecimal(7));
        return new Row(place,rs.getString(8));
    }
    private static Place mapDetail(ResultSet rs,int ignored)throws SQLException {
        return new Place(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
            rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getBigDecimal(9));
    }

    record Page(List<Place> items,String nextCursor) {}
    record Place(UUID placeId,String name,String category,String regionLabel,String address,String roadAddress,
        String description,BigDecimal latitude,BigDecimal longitude) {}
    private record Row(Place place,String normalizedName) {}
}
