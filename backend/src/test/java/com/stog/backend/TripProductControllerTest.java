package com.stog.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockPart;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TripProductController.class)
@Import({SecurityConfig.class, ApiErrorHandler.class, JsonConfig.class, TripCollaborationController.class,
    TripCoverController.class})
@EnableWebSecurity
class TripProductControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TripProductService service;
    @MockitoBean TripCollaborationService collaborationService;
    @MockitoBean TripCoverService coverService;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TRIP = UUID.fromString("00000000-0000-0000-0000-00000000ab12");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID PLACE = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

    @Test
    void authenticatedPrincipalCreatesAndReplaysExactWire() throws Exception {
        var trip = new TripProductService.TripView(TRIP, "제주 여행", "제주", LocalDate.of(2026, 10, 10),
            LocalDate.of(2026, 10, 12), "UPCOMING", AS_OF, "ADMIN", 1, 0);
        when(service.create(eq(USER), eq("제주 여행"), eq("제주"), any(), any(), eq(REQUEST)))
            .thenReturn(new TripProductService.CreateResult(trip, true),
                new TripProductService.CreateResult(trip, false));

        var first = mvc.perform(post("/api/trips").with(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(createBody("  제주 여행  ", "제주", "2026-10-10", "2026-10-12", REQUEST)));
        first.andExpect(status().isCreated())
            .andExpect(jsonPath("$.trip_id").value(TRIP.toString()))
            .andExpect(jsonPath("$.name").value("제주 여행"))
            .andExpect(jsonPath("$.region_label").value("제주"))
            .andExpect(jsonPath("$.start_date").value("2026-10-10"))
            .andExpect(jsonPath("$.end_date").value("2026-10-12"))
            .andExpect(jsonPath("$.status").value("UPCOMING"))
            .andExpect(jsonPath("$.as_of_date").value("2026-10-01"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.member_count").value(1))
            .andExpect(jsonPath("$.plan_version").value(0))
            .andExpect(jsonPath("$.cover").value(nullValue()));
        mvc.perform(post("/api/trips").with(auth()).contentType(MediaType.APPLICATION_JSON)
                .content(createBody("제주 여행", "제주", "2026-10-10", "2026-10-12", REQUEST)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.trip_id").value(TRIP.toString()));
    }

    @Test
    void validatesExactCreateListDetailAndBasketContracts() throws Exception {
        for (String body : List.of(
                createBody("x", "r", "2026-02-30", "2026-03-01", REQUEST),
                createBody("x\n", "r", "2026-03-01", "2026-03-01", REQUEST),
                createBody("x", "r", "2026-03-02", "2026-03-01", REQUEST),
                "{\"name\":null,\"region_label\":\"r\",\"start_date\":\"2026-03-01\",\"end_date\":\"2026-03-01\",\"request_id\":\"" + REQUEST + "\"}",
                createBody("x", "r", "2026-03-01", "2026-03-01", REQUEST).replace("}", ",\"unknown\":1}"),
                createBody("x", "r", "2026-03-01", "2026-03-01", REQUEST).replace("\"name\":\"x\"", "\"name\":\"x\",\"name\":\"y\""))) {
            mvc.perform(post("/api/trips").with(auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/trips?limit=0").with(auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/trips?limit=20&limit=20").with(auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/trips?other=1").with(auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/trips/{id}/trip-items?limit=20", TRIP).with(auth()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/trips/{id}/trip-items?state=SCHEDULED", TRIP).with(auth()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/trips/{id}", TRIP.toString().toUpperCase()).with(auth()))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void listDetailAndBasketSerializeRequiredNullableFields() throws Exception {
        var item = new TripProductService.TripItem(TRIP, null, null, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 2), "PAST", "MEMBER", 2, 7);
        when(service.trips(USER, 20, null)).thenReturn(new TripProductService.TripPage(AS_OF, List.of(item), null));
        when(service.detail(USER, TRIP)).thenReturn(new TripProductService.TripView(TRIP, null, null,
            item.startDate(), item.endDate(), item.status(), AS_OF, item.role(), item.memberCount(), item.planVersion()));
        when(service.basket(USER, TRIP, 20, null)).thenReturn(new TripProductService.BasketPage(TRIP, 7,
            List.of(new TripProductService.BasketItem(ITEM, PLACE, "BASKET", null, null,
                Instant.parse("2026-10-01T03:04:05Z"))), null));

        mvc.perform(get("/api/trips").with(auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.as_of_date").value("2026-10-01"))
            .andExpect(jsonPath("$.items[0].name").value(nullValue()))
            .andExpect(jsonPath("$.items[0].region_label").value(nullValue()))
            .andExpect(jsonPath("$.items[0].cover").value(nullValue()))
            .andExpect(jsonPath("$.next_cursor").value(nullValue()));
        mvc.perform(get("/api/trips/{id}", TRIP).with(auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.as_of_date").value("2026-10-01"));
        mvc.perform(get("/api/trips/{id}/trip-items?state=BASKET", TRIP).with(auth()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.trip_id").value(TRIP.toString()))
            .andExpect(jsonPath("$.plan_version").value(7))
            .andExpect(jsonPath("$.items[0].trip_item_id").value(ITEM.toString()))
            .andExpect(jsonPath("$.items[0].place_id").value(PLACE.toString()))
            .andExpect(jsonPath("$.items[0].state").value("BASKET"))
            .andExpect(jsonPath("$.items[0].schedule_date").value(nullValue()))
            .andExpect(jsonPath("$.items[0].sort_order").value(nullValue()))
            .andExpect(jsonPath("$.items[0].created_at").value("2026-10-01T03:04:05Z"))
            .andExpect(jsonPath("$.next_cursor").value(nullValue()));
        verify(service).trips(USER, 20, null);
    }

    @Test
    void authenticatedMemberCanReachMetadataEditHttpContract() throws Exception {
        var result = new TripCollaborationService.TripView(TRIP, "제주 가을 여행", "제주",
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13), "UPCOMING", AS_OF, "MEMBER", 2, 5);
        when(collaborationService.updateTrip(eq(USER), eq(TRIP), eq("제주 가을 여행"), eq("제주"), any(), any(),
            eq(4L), eq(REQUEST))).thenReturn(result);
        mvc.perform(put("/api/trips/{id}", TRIP).with(auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"제주 가을 여행\",\"region_label\":\"제주\","
                    + "\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-13\","
                    + "\"expected_plan_version\":4,\"request_id\":\"" + REQUEST + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("제주 가을 여행"));
    }

    @Test
    void authenticatedMemberCanReachMultipartCoverContract() throws Exception {
        UUID coverId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var cover = new TripCoverService.CoverView(coverId, "image/jpeg", 123, 20, 10,
            45, 20, 10, "/api/trips/" + TRIP + "/covers/" + coverId + "/file",
            "/api/trips/" + TRIP + "/covers/" + coverId + "/thumbnail");
        when(coverService.upload(eq(USER), eq(TRIP), eq(4L), eq(REQUEST), eq("image/png"), anyLong(), any()))
            .thenReturn(new TripCoverService.UploadResult("SET", cover, 5, true));
        MockPart image = new MockPart("image", "ignored.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        image.getHeaders().setContentType(MediaType.IMAGE_PNG);
        MockPart command = new MockPart("command",
            ("{\"expected_plan_version\":4,\"request_id\":\"" + REQUEST + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        command.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        mvc.perform(multipart("/api/trips/{id}/cover", TRIP).part(image, command).with(auth()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.cover.cover_id").value(coverId.toString()))
            .andExpect(jsonPath("$.cover.file_path").value(cover.filePath()))
            .andExpect(jsonPath("$.plan_version").value(5));
    }

    @Test
    void unauthenticatedTripRoutesReturnAuthenticationEnvelope() throws Exception {
        mvc.perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/trips")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trips/{id}", TRIP)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trips/{id}/trip-items?state=BASKET", TRIP)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void planConflictIsOnlyErrorWithCurrentVersion() throws Exception {
        when(service.basket(eq(USER), eq(TRIP), anyInt(), any()))
            .thenThrow(new TripProductException(409, "PLAN_VERSION_CONFLICT", "changed", 9L));
        mvc.perform(get("/api/trips/{id}/trip-items?state=BASKET&cursor=opaque", TRIP).with(auth()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PLAN_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.current_plan_version").value(9));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, List.of()));
    }

    private static String createBody(String name, String region, String start, String end, UUID request) {
        return "{\"name\":\"" + name + "\",\"region_label\":\"" + region + "\",\"start_date\":\""
            + start + "\",\"end_date\":\"" + end + "\",\"request_id\":\"" + request + "\"}";
    }
}
