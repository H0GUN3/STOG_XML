package com.stog.backend;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class TripCoverMultipartHttpIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID TRIP = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final UUID COVER = UUID.fromString("00000000-0000-0000-0000-000000000074");
    private static final byte[] IMAGE = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.default-schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.schemas", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.create-schemas", () -> "false");
    }

    @Value("${local.server.port}") int serverPort;
    @MockitoBean AuthService authService;
    @MockitoBean TripCoverService coverService;

    @BeforeEach
    void responseFixture() {
        when(authService.authenticateAccess("multipart-boundary-access")).thenReturn(new AuthenticatedUser(USER));
        TripCoverService.CoverView cover = new TripCoverService.CoverView(COVER, "image/jpeg", 123, 20, 10,
            45, 20, 10, "/api/trips/" + TRIP + "/covers/" + COVER + "/file",
            "/api/trips/" + TRIP + "/covers/" + COVER + "/thumbnail");
        when(coverService.upload(eq(USER), eq(TRIP), eq(4L), eq(REQUEST), eq("image/png"), anyLong(), any()))
            .thenReturn(new TripCoverService.UploadResult("SET", cover, 5, true));
        clearInvocations(coverService);
    }

    @Test
    void androidShapedImageAndFilenameLessJsonCommandReachControllerService() throws Exception {
        HttpResponse<String> response = send("", List.of(image(), command()));

        assertEquals(201, response.statusCode(), response.body());
        verify(coverService).upload(eq(USER), eq(TRIP), eq(4L), eq(REQUEST), eq("image/png"),
            eq((long) IMAGE.length), any());
    }

    @Test
    void actualUrlQueryIsRejectedIndependentlyOfMultipartFormParts() throws Exception {
        HttpResponse<String> response = send("?unexpected=1", List.of(image(), command()));

        assertInvalid(response);
        verifyNoInteractions(coverService);
    }

    @Test
    void duplicateImageOrFilenameLessCommandPartsAreRejected() throws Exception {
        assertInvalid(send("", List.of(image(), image(), command())));
        assertInvalid(send("", List.of(image(), command(), command())));
        verifyNoInteractions(coverService);
    }

    @Test
    void unknownFileAndTextFormPartsAreRejected() throws Exception {
        assertInvalid(send("", List.of(image(), command(), filePart("extra", "extra.bin", "application/octet-stream", new byte[] {1}))));
        assertInvalid(send("", List.of(image(), command(), textPart("extra", "text/plain", "value"))));
        verifyNoInteractions(coverService);
    }

    @Test
    void deniedCoverCompletesHeadersAndJsonBodyForAndroidAndGenericAcceptHeaders() throws Exception {
        when(coverService.file(eq(USER), eq(TRIP), eq(COVER), eq(false)))
            .thenThrow(new TripCoverException(404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE",
                "The trip was not found or is not accessible"));
        for (String accept : List.of("image/jpeg", "*/*", "application/json")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort
                    + "/api/trips/" + TRIP + "/covers/" + COVER + "/file"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer multipart-boundary-access")
                .header("Accept", accept).GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) { body = input.readAllBytes(); }
            assertEquals(404, response.statusCode(), accept + ": " + new String(body, StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertTrue(new String(body, StandardCharsets.UTF_8)
                .contains("\"code\":\"TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE\""), accept);
        }
    }

    private HttpResponse<String> send(String query, List<RawPart> parts) throws Exception {
        String boundary = "stog-real-servlet-boundary";
        byte[] body = multipart(boundary, parts);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort
                + "/api/trips/" + TRIP + "/cover" + query))
            .header("Authorization", "Bearer multipart-boundary-access")
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] multipart(String boundary, List<RawPart> parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (RawPart part : parts) {
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name()
                + "\"" + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"")
                + "\r\nContent-Type: " + part.contentType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(part.body());
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static RawPart image() { return filePart("image", "cover", "image/png", IMAGE); }
    private static RawPart command() {
        return textPart("command", "application/json; charset=utf-8",
            "{\"expected_plan_version\":4,\"request_id\":\"" + REQUEST + "\"}");
    }
    private static RawPart filePart(String name, String filename, String contentType, byte[] body) {
        return new RawPart(name, filename, contentType, body);
    }
    private static RawPart textPart(String name, String contentType, String body) {
        return new RawPart(name, null, contentType, body.getBytes(StandardCharsets.UTF_8));
    }
    private static void assertInvalid(HttpResponse<String> response) {
        assertEquals(400, response.statusCode(), response.body());
        org.junit.jupiter.api.Assertions.assertTrue(response.body().contains("\"code\":\"INVALID_REQUEST\""),
            response.body());
    }

    private record RawPart(String name, String filename, String contentType, byte[] body) {}
}
