package com.stog.backend;

import java.time.LocalDate;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BasketSaveController.class)
@Import({SecurityConfig.class, ApiErrorHandler.class})
@EnableWebSecurity
class BasketSaveControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BasketSaveService service;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIP = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PLACE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void authenticatedInternalPrincipalGetsMinimalCurrentItemResponse() throws Exception {
        when(service.currentItem(USER, TRIP, ITEM))
            .thenReturn(new BasketSaveService.CurrentItem(ITEM, PLACE, "SCHEDULED", LocalDate.of(2030, 1, 2), 4L, 8));

        mvc.perform(get("/api/trips/{tripId}/trip-items/{tripItemId}", TRIP, ITEM)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, java.util.List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trip_item_id").value(ITEM.toString()))
            .andExpect(jsonPath("$.place_id").value(PLACE.toString()))
            .andExpect(jsonPath("$.state").value("SCHEDULED"))
            .andExpect(jsonPath("$.schedule_date").value("2030-01-02"))
            .andExpect(jsonPath("$.sort_order").value(4))
            .andExpect(jsonPath("$.plan_version").value(8))
            .andExpect(jsonPath("$.trip_id").doesNotExist())
            .andExpect(jsonPath("$.created_by_user_id").doesNotExist());
        verify(service).currentItem(USER, TRIP, ITEM);
    }

    @Test
    void authenticatedInternalPrincipalGetsMinimalCreatedResponse() throws Exception {
        when(service.save(any(), any(), any(), anyLong(), any()))
            .thenReturn(new BasketSaveService.Result("CREATED", ITEM, PLACE, 8));

        mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, java.util.List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PLACE, 7, REQUEST)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.result").value("CREATED"))
            .andExpect(jsonPath("$.trip_item_id").value(ITEM.toString()))
            .andExpect(jsonPath("$.place_id").value(PLACE.toString()))
            .andExpect(jsonPath("$.plan_version").value(8))
            .andExpect(jsonPath("$.trip_id").doesNotExist());
    }

    @Test
    void rejectsUnknownFieldsAndNoncanonicalUuid() throws Exception {
        var auth = authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, java.util.List.of()));
        mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP).with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"place_id\":\"" + PLACE + "\",\"expected_plan_version\":0,\"request_id\":\"" + REQUEST + "\",\"user_id\":\"forged\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP).with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PLACE, 0, REQUEST).toUpperCase()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsFullNonnegativeInt64BoundaryWithoutCoercion() throws Exception {
        when(service.save(any(), any(), any(), anyLong(), any()))
            .thenReturn(new BasketSaveService.Result("ALREADY_EXISTS", ITEM, PLACE, Long.MAX_VALUE));

        mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, java.util.List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PLACE, Long.MAX_VALUE, REQUEST)))
            .andExpect(status().isOk());
        verify(service).save(USER, TRIP, PLACE, Long.MAX_VALUE, REQUEST);
    }

    @Test
    void rejectsFractionalStringAndOutOfRangePlanVersions() throws Exception {
        var auth = authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER), null, java.util.List.of()));
        for (String invalid : java.util.List.of("1.5", "\"1\"", "9223372036854775808", "-1")) {
            mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP).with(auth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"place_id\":\"" + PLACE + "\",\"expected_plan_version\":" + invalid
                        + ",\"request_id\":\"" + REQUEST + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void authenticatedStringPrincipalIsNotTrustedAsInternalIdentity() throws Exception {
        var untrusted = authentication(new UsernamePasswordAuthenticationToken(USER.toString(), null, java.util.List.of()));
        mvc.perform(post("/api/trips/{tripId}/trip-items", TRIP)
                .with(untrusted).contentType(MediaType.APPLICATION_JSON).content(body(PLACE, 0, REQUEST)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/trips/{tripId}/trip-items/{tripItemId}", TRIP, ITEM).with(untrusted))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    private static String body(UUID place, long version, UUID request) {
        return "{\"place_id\":\"" + place + "\",\"expected_plan_version\":" + version
            + ",\"request_id\":\"" + request + "\"}";
    }
}
