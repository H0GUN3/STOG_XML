package com.stog.backend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers=BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class PlaceCatalogHttpIntegrationTest {
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static final UUID USER=UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID OTHER=UUID.fromString("00000000-0000-0000-0000-000000000092");
    @Value("${local.server.port}")int serverPort;
    @MockitoBean AuthService authService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final java.util.ArrayList<UUID> places=new java.util.ArrayList<>(),trips=new java.util.ArrayList<>();

    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        p.add("spring.datasource.username",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        p.add("spring.datasource.password",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        p.add("spring.datasource.hikari.schema",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        p.add("spring.flyway.default-schema",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        p.add("spring.flyway.schemas",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        p.add("spring.flyway.create-schemas",()->"false");
    }
    @BeforeEach void setup(){when(authService.authenticateAccess("place-access")).thenReturn(new AuthenticatedUser(USER));when(authService.authenticateAccess("other-access")).thenReturn(new AuthenticatedUser(OTHER));}
    @AfterEach void cleanup(){
        for(UUID trip:trips){jdbc.update("DELETE FROM request_execution WHERE input_trip_id=?",trip);jdbc.update("DELETE FROM trip_item WHERE trip_id=?",trip);jdbc.update("DELETE FROM trip_member WHERE trip_id=?",trip);jdbc.update("DELETE FROM trip WHERE id=?",trip);}
        for(UUID place:places)jdbc.update("DELETE FROM place WHERE id=?",place);
        jdbc.update("DELETE FROM stog_user WHERE id IN (?,?)",USER,OTHER);
    }

    @Test void unicodeFiltersKeysetDetailAndReadsAreExactAndReadOnly()throws Exception{
        UUID one=place("SEMAS","1","제주 카페","제주 카페","카페","카페","제주","제주","주소1","도로1","설명",33.1,126.1);
        UUID two=place("SEMAS","2","제주 카페 둘","제주 카페 둘","카페","카페","제주","제주",null,null,null,null,null);
        place(null,null,null,null,null,null,null,null,null,null,null,null,null);
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM place WHERE id=?",one);int items=count("SELECT count(*) FROM trip_item");
        JsonNode first=body(get("place-access","/api/places?query="+enc("  제주   카페  ")+"&region="+enc("제주")+"&category="+enc("카페")+"&limit=1","application/json"));
        assertEquals(one.toString(),first.at("/items/0/place_id").asText());String cursor=first.get("next_cursor").asText();assertFalse(cursor.isBlank());
        JsonNode second=body(get("place-access","/api/places?query="+enc("제주 카페")+"&region="+enc("제주")+"&category="+enc("카페")+"&limit=1&cursor="+enc(cursor),"application/json"));
        assertEquals(two.toString(),second.at("/items/0/place_id").asText());assertTrue(second.get("next_cursor").isNull());
        JsonNode detail=body(get("place-access","/api/places/"+one,"application/json"));assertEquals("도로1",detail.get("road_address").asText());assertEquals("설명",detail.get("description").asText());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM place WHERE id=?",one));assertEquals(items,count("SELECT count(*) FROM trip_item"));
    }

    @Test void literalLikeCharactersLegacyMissingDuplicateAndErrorNegotiationAreExact()throws Exception{
        UUID percent=place("S","p","100% 카페","100% 카페",null,null,null,null,null,null,null,null,null);
        place("S","u","100x 카페","100x 카페",null,null,null,null,null,null,null,null,null);
        UUID underscore=place("S","us","under_score","under_score",null,null,null,null,null,null,null,null,null);
        place("S","ux","underxscore","underxscore",null,null,null,null,null,null,null,null,null);
        UUID slash=place("S","bs","path\\shop","path\\shop",null,null,null,null,null,null,null,null,null);
        place("S","bx","pathxshop","pathxshop",null,null,null,null,null,null,null,null,null);
        JsonNode found=body(get("place-access","/api/places?query="+enc("100%"),"application/json"));assertEquals(1,found.get("items").size());assertEquals(percent.toString(),found.at("/items/0/place_id").asText());
        JsonNode foundUnderscore=body(get("place-access","/api/places?query="+enc("under_score"),"application/json"));assertEquals(underscore.toString(),foundUnderscore.at("/items/0/place_id").asText());assertEquals(1,foundUnderscore.get("items").size());
        JsonNode foundSlash=body(get("place-access","/api/places?query="+enc("path\\shop"),"application/json"));assertEquals(slash.toString(),foundSlash.at("/items/0/place_id").asText());assertEquals(1,foundSlash.get("items").size());
        UUID legacy=place(null,null,null,null,null,null,null,null,null,null,null,null,null);
        HttpResponse<String> legacyResponse=get("place-access","/api/places/"+legacy,"image/jpeg");assertError(legacyResponse,404,"PLACE_NOT_FOUND");assertTrue(legacyResponse.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertError(get("place-access","/api/places/"+UUID.randomUUID(),"application/json"),404,"PLACE_NOT_FOUND");
        assertError(get("place-access","/api/places?query=ab&query=cd","image/jpeg"),400,"INVALID_REQUEST");
        assertError(get(null,"/api/places?query=ab","application/json"),401,"AUTHENTICATION_REQUIRED");
    }

    @Test void cursorBindsPrincipalFilterAndLimitAndExistingBasketReplayUsesSamePlace()throws Exception{
        UUID place=place("S","1","가게 하나","가게 하나","식당","식당","서울","서울",null,null,null,null,null);
        place("S","2","가게 둘","가게 둘","식당","식당","서울","서울",null,null,null,null,null);
        JsonNode first=body(get("place-access","/api/places?query="+enc("가게")+"&limit=1","application/json"));String cursor=first.get("next_cursor").asText();
        assertError(get("other-access","/api/places?query="+enc("가게")+"&limit=1&cursor="+enc(cursor),"application/json"),400,"INVALID_CURSOR");
        assertError(get("place-access","/api/places?query="+enc("다른")+"&limit=1&cursor="+enc(cursor),"application/json"),400,"INVALID_CURSOR");
        assertError(get("place-access","/api/places?query="+enc("가게")+"&limit=2&cursor="+enc(cursor),"application/json"),400,"INVALID_CURSOR");
        jdbc.update("INSERT INTO stog_user(id) VALUES (?),(?)",USER,OTHER);UUID trip=UUID.randomUUID();trips.add(trip);
        jdbc.update("INSERT INTO trip(id,name,region_label,start_date,end_date,plan_version) VALUES (?,?,?,?,?,0)",trip,"trip","서울",LocalDate.now(),LocalDate.now().plusDays(1));
        jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,'ADMIN')",trip,USER);UUID request=UUID.randomUUID();String payload="{\"place_id\":\""+place+"\",\"expected_plan_version\":0,\"request_id\":\""+request+"\"}";
        HttpResponse<String> created=post("place-access","/api/trips/"+trip+"/trip-items",payload);assertEquals(201,created.statusCode());
        HttpResponse<String> replay=post("place-access","/api/trips/"+trip+"/trip-items",payload);assertEquals(201,replay.statusCode());assertEquals(json.readTree(created.body()),json.readTree(replay.body()));assertEquals(1,count("SELECT count(*) FROM trip_item WHERE trip_id=? AND place_id=?",trip,place));
    }

    private UUID place(String st,String sk,String name,String normalized,String category,String normalizedCategory,String region,String normalizedRegion,String address,String road,String description,Double lat,Double lon){UUID id=UUID.randomUUID();places.add(id);jdbc.update("INSERT INTO place(id,source_type,source_key,name,normalized_name,category,normalized_category,region_label,normalized_region_label,address,road_address,description,latitude,longitude) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,st,sk,name,normalized,category,normalizedCategory,region,normalizedRegion,address,road,description,lat,lon);return id;}
    private HttpResponse<String> get(String token,String path,String accept)throws Exception{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+serverPort+path)).header("Accept",accept);if(token!=null)b.header("Authorization","Bearer "+token);return HTTP.send(b.GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpResponse<String> post(String token,String path,String body)throws Exception{return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+serverPort+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private JsonNode body(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());}
    private void assertError(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,json.readTree(response.body()).at("/error/code").asText());}
    private int count(String sql,Object...args){return jdbc.queryForObject(sql,Integer.class,args);}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
