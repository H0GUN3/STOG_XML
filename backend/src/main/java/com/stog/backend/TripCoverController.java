package com.stog.backend;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/trips")
public class TripCoverController {
    private final TripCoverService service;

    public TripCoverController(TripCoverService service) {
        this.service = service;
    }

    @PostMapping(value = "/{tripId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SetCoverResponse> upload(@PathVariable String tripId,
            @RequestPart("image") MultipartFile image,
            @Valid @RequestPart("command") CoverCommand command,
            Authentication authentication, HttpServletRequest request) {
        UUID principal = principal(authentication);
        requireNoQuery(request);
        requireExactParts(request, image);
        String mediaType = exactImageType(image.getContentType());
        long version = version(command.expectedPlanVersion());
        UUID requestId = TripProductController.canonicalUuid(command.requestId());
        try {
            TripCoverService.UploadResult result = service.upload(principal,
                TripProductController.canonicalUuid(tripId), version, requestId, mediaType,
                image.getSize(), image.getInputStream());
            return privateResponse(result.created() ? 201 : 200,
                new SetCoverResponse(result.result(), response(result.cover()), result.planVersion()));
        } catch (IOException exception) {
            throw new TripCoverException(503, "MEDIA_STORAGE_FAILED", "The cover image could not be stored");
        }
    }

    @DeleteMapping(value = "/{tripId}/cover", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RemoveCoverResponse> remove(@PathVariable String tripId,
            @Valid @RequestBody CoverCommand command, Authentication authentication, HttpServletRequest request) {
        UUID principal = principal(authentication);
        requireNoQuery(request);
        TripCoverService.RemoveResult result = service.remove(principal,
            TripProductController.canonicalUuid(tripId), version(command.expectedPlanVersion()),
            TripProductController.canonicalUuid(command.requestId()));
        return privateResponse(200, new RemoveCoverResponse(result.result(), result.planVersion()));
    }

    @GetMapping("/{tripId}/covers/{coverId}/file")
    ResponseEntity<byte[]> file(@PathVariable String tripId, @PathVariable String coverId,
            Authentication authentication, HttpServletRequest request) {
        return bytes(tripId, coverId, authentication, request, false);
    }

    @GetMapping("/{tripId}/covers/{coverId}/thumbnail")
    ResponseEntity<byte[]> thumbnail(@PathVariable String tripId, @PathVariable String coverId,
            Authentication authentication, HttpServletRequest request) {
        return bytes(tripId, coverId, authentication, request, true);
    }

    private ResponseEntity<byte[]> bytes(String tripId, String coverId, Authentication authentication,
            HttpServletRequest request, boolean thumbnail) {
        UUID principal = principal(authentication);
        requireNoQuery(request);
        TripCoverService.FileResult result = service.file(principal,
            TripProductController.canonicalUuid(tripId), TripProductController.canonicalUuid(coverId), thumbnail);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .contentLength(result.bytes().length).body(result.bytes());
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new TripCoverException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        return user.userId();
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }

    private static void requireExactParts(HttpServletRequest request, MultipartFile image) {
        if (image.isEmpty()) throw invalidCover();
        int imageCount = 0;
        int commandCount = 0;
        int total = 0;
        try {
            for (Part part : request.getParts()) {
                total++;
                if ("image".equals(part.getName())) {
                    imageCount++;
                    if (part.getSubmittedFileName() == null) throw invalid();
                } else if ("command".equals(part.getName())) {
                    commandCount++;
                    if (part.getSubmittedFileName() != null) throw invalid();
                } else {
                    throw invalid();
                }
            }
        } catch (IOException | ServletException exception) {
            throw invalid();
        }
        if (total != 2 || imageCount != 1 || commandCount != 1) throw invalid();
    }

    private static String exactImageType(String value) {
        if ("image/jpeg".equals(value) || "image/png".equals(value)) return value;
        throw new TripCoverException(415, "UNSUPPORTED_MEDIA_TYPE", "The cover media type is unsupported");
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw invalid();
        return value;
    }

    private static CoverResponse response(TripCoverService.CoverView value) {
        return new CoverResponse(value.coverId(), value.mediaType(), value.byteSize(), value.width(), value.height(),
            value.thumbnailByteSize(), value.thumbnailWidth(), value.thumbnailHeight(), value.filePath(),
            value.thumbnailPath());
    }

    private static <T> ResponseEntity<T> privateResponse(int status, T body) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff").body(body);
    }

    private static TripCoverException invalid() {
        return new TripCoverException(400, "INVALID_REQUEST", "The request is invalid");
    }

    private static TripCoverException invalidCover() {
        return new TripCoverException(400, "INVALID_COVER", "The cover image is invalid");
    }

    record CoverCommand(@JsonProperty("expected_plan_version") @NotNull Long expectedPlanVersion,
        @JsonProperty("request_id") @NotNull String requestId) {}
    record SetCoverResponse(String result, TripCoverController.CoverResponse cover,
        @JsonProperty("plan_version") long planVersion) {}
    record RemoveCoverResponse(String result, @JsonProperty("plan_version") long planVersion) {}
    record CoverResponse(@JsonProperty("cover_id") UUID coverId,
        @JsonProperty("media_type") String mediaType, @JsonProperty("byte_size") long byteSize,
        int width, int height, @JsonProperty("thumbnail_byte_size") long thumbnailByteSize,
        @JsonProperty("thumbnail_width") int thumbnailWidth,
        @JsonProperty("thumbnail_height") int thumbnailHeight,
        @JsonProperty("file_path") String filePath,
        @JsonProperty("thumbnail_path") String thumbnailPath) {}
}
