package com.stog.backend;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers=BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
final class SemasPlaceImporterPostgresIntegrationTest {
    private static final UUID USER=UUID.fromString("00000000-0000-0000-0000-000000000094");
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    @Autowired DataSource dataSource; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @MockitoBean AuthService authService; @Value("${local.server.port}")int port;
    private final SemasPlaceImporter importer=new SemasPlaceImporter();

    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));p.add("spring.datasource.username",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));p.add("spring.datasource.password",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));p.add("spring.datasource.hikari.schema",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));p.add("spring.flyway.default-schema",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));p.add("spring.flyway.schemas",()->BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));p.add("spring.flyway.create-schemas",()->"false");
    }
    @BeforeEach void setup(){when(authService.authenticateAccess("import-access")).thenReturn(new AuthenticatedUser(USER));}
    @AfterEach void cleanup(){jdbc.execute("DROP TRIGGER IF EXISTS semas_test_reject ON place");jdbc.execute("DROP FUNCTION IF EXISTS semas_test_reject()");jdbc.update("DELETE FROM place WHERE source_type=?",SemasPlaceImporter.SOURCE_TYPE);}

    @Test void repeatAndUpdatePreserveIdentityWhileDistinctSourceKeysRemainSeparate()throws Exception{
        UUID legacy=UUID.randomUUID();jdbc.update("INSERT INTO place(id)VALUES(?)",legacy);
        SemasPlaceImporter.ImportReport first=run(csv(row("KEY-1","  Cafe\u0301\u00a0Name  ","Branch A","S001","카페","제주특별자치도","주소, 1","도로 1","126.1234565","33.1234564"),row("KEY-2","  Cafe\u0301\u00a0Name  ","","S001","카페","제주특별자치도","주소, 1","도로 1","126.1234565","33.1234564")));
        assertEquals(2,first.inserted());assertEquals(1,first.nonblankBranches());assertEquals(1,first.distinctCategoryCodes());assertEquals(1,first.reviewDuplicateGroups());
        UUID id=jdbc.queryForObject("SELECT id FROM place WHERE source_type=? AND source_key='KEY-1'",UUID.class,SemasPlaceImporter.SOURCE_TYPE);
        assertEquals("Café Name",jdbc.queryForObject("SELECT name FROM place WHERE id=?",String.class,id));assertEquals("café name",jdbc.queryForObject("SELECT normalized_name FROM place WHERE id=?",String.class,id));assertEquals("126.123457|33.123456",jdbc.queryForObject("SELECT longitude||'|'||latitude FROM place WHERE id=?",String.class,id));
        SemasPlaceImporter.ImportReport repeat=run(csv(row("KEY-1","  Cafe\u0301\u00a0Name  ","Branch A","S001","카페","제주특별자치도","주소, 1","도로 1","126.1234565","33.1234564"),row("KEY-2","  Cafe\u0301\u00a0Name  ","","S001","카페","제주특별자치도","주소, 1","도로 1","126.1234565","33.1234564")));
        assertEquals(0,repeat.inserted());assertEquals(2,repeat.updated());assertEquals(2,count());assertEquals(id,jdbc.queryForObject("SELECT id FROM place WHERE source_type=? AND source_key='KEY-1'",UUID.class,SemasPlaceImporter.SOURCE_TYPE));
        run(csv(row("KEY-1","Changed Name","","S002","식당","제주특별자치도","주소 2","","126.2","33.2")));
        assertEquals(id,jdbc.queryForObject("SELECT id FROM place WHERE source_type=? AND source_key='KEY-1'",UUID.class,SemasPlaceImporter.SOURCE_TYPE));assertEquals("Changed Name",jdbc.queryForObject("SELECT name FROM place WHERE id=?",String.class,id));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM place WHERE id=?",Integer.class,legacy));
    }

    @Test void invalidCoordinateAndDuplicateSourceKeyRollbackWholeEntry()throws Exception{
        String valid=row("ROLL-1","Valid","","S001","카페","제주","주소","도로","126.1","33.1");
        SemasPlaceImporter.ImportFailure coordinate=assertThrows(SemasPlaceImporter.ImportFailure.class,()->run(csv(valid,row("ROLL-2","Bad","","S001","카페","제주","주소","도로","126.2","91"))));
        assertEquals("INVALID_LATITUDE",coordinate.reason());assertEquals(0,count());
        SemasPlaceImporter.ImportFailure duplicate=assertThrows(SemasPlaceImporter.ImportFailure.class,()->run(csv(valid,valid)));
        assertEquals("DUPLICATE_SOURCE_KEY",duplicate.reason());assertEquals(0,count());
    }

    @Test void databaseFailureRollsBackAndCleanRetrySucceeds()throws Exception{
        jdbc.execute("CREATE FUNCTION semas_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name='Trigger Fail' THEN RAISE EXCEPTION 'fixture reject'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER semas_test_reject BEFORE INSERT OR UPDATE ON place FOR EACH ROW EXECUTE FUNCTION semas_test_reject()");
        String input=csv(row("TX-1","Good","","S001","카페","제주","주소","도로","126.1","33.1"),row("TX-2","Trigger Fail","","S001","카페","제주","주소","도로","126.2","33.2"));
        assertThrows(SemasPlaceImporter.ImportFailure.class,()->run(input));assertEquals(0,count());
        jdbc.execute("DROP TRIGGER semas_test_reject ON place");jdbc.execute("DROP FUNCTION semas_test_reject()");
        assertEquals(2,run(input).inserted());assertEquals(2,count());
    }

    @Test void importedPlaceIsReturnedByAuthenticatedRealHttpSearchAndDetail()throws Exception{
        run(csv(row("HTTP-1","서울 \"인용\" 카페","본점","S001","커피/음료","서울특별시","서울 주소","서울 도로","127.001","37.501")));
        HttpResponse<String> search=get("/api/places?query="+enc("서울 \"인용\"")+"&region="+enc("서울특별시")+"&category="+enc("커피/음료"));assertEquals(200,search.statusCode());JsonNode body=json.readTree(search.body());assertEquals(1,body.get("items").size());String id=body.at("/items/0/place_id").asText();
        HttpResponse<String> detail=get("/api/places/"+id);assertEquals(200,detail.statusCode());JsonNode place=json.readTree(detail.body());assertEquals("서울 \"인용\" 카페",place.get("name").asText());assertEquals("서울 도로",place.get("road_address").asText());assertEquals(id,jdbc.queryForObject("SELECT id::text FROM place WHERE source_type=? AND source_key='HTTP-1'",String.class,SemasPlaceImporter.SOURCE_TYPE));
    }

    private SemasPlaceImporter.ImportReport run(String csv)throws Exception{try(Connection connection=dataSource.getConnection()){return importer.importCsv(connection,new StringReader(csv),"synthetic.csv",0);}}
    private int count(){return jdbc.queryForObject("SELECT count(*) FROM place WHERE source_type=?",Integer.class,SemasPlaceImporter.SOURCE_TYPE);}
    private HttpResponse<String> get(String path)throws Exception{return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer import-access").GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String csv(String...rows){return encode(SemasPlaceImporter.HEADER)+"\r\n"+String.join("\r\n",rows)+"\r\n";}
    private static String row(String key,String name,String branch,String categoryCode,String category,String region,String address,String road,String longitude,String latitude){ArrayList<String> values=new ArrayList<>();for(int i=0;i<39;i++)values.add("");values.set(0,key);values.set(1,name);values.set(2,branch);values.set(7,categoryCode);values.set(8,category);values.set(12,region);values.set(24,address);values.set(31,road);values.set(37,longitude);values.set(38,latitude);return encode(values);}
    private static String encode(List<String> values){return values.stream().map(SemasPlaceImporterPostgresIntegrationTest::quote).reduce((a,b)->a+","+b).orElse("");}
    private static String quote(String value){return value.indexOf(',')>=0||value.indexOf('"')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0?'"'+value.replace("\"","\"\"")+'"':value;}
}
