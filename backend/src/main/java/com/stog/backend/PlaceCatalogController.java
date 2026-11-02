package com.stog.backend;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
final class PlaceCatalogController {
    private final PlaceCatalogService service;
    PlaceCatalogController(PlaceCatalogService service) { this.service=service; }

    @GetMapping
    ResponseEntity<SearchResponse> search(Authentication authentication,HttpServletRequest request) {
        UUID principal=principal(authentication);
        Map<String,String[]> parameters=requireQueries(request,Set.of("query","region","category","limit","cursor"));
        String query=searchKey(required(parameters,"query"),2,100);
        String region=optionalNormalized(parameters,"region");
        String category=optionalNormalized(parameters,"category");
        String cursor=optional(parameters,"cursor");
        if (cursor!=null && cursor.isEmpty()) throw invalidCursor();
        int limit=parseLimit(optional(parameters,"limit"),cursor!=null);
        PlaceCatalogService.Page page=service.search(principal,query,region,category,limit,cursor);
        return privateJson(new SearchResponse(page.items().stream().map(PlaceCatalogController::summary).toList(),page.nextCursor()));
    }

    @GetMapping("/{placeId}")
    ResponseEntity<DetailResponse> detail(@PathVariable String placeId,Authentication authentication,HttpServletRequest request) {
        principal(authentication); requireQueries(request,Set.of());
        PlaceCatalogService.Place place=service.detail(TripProductController.canonicalUuid(placeId));
        return privateJson(new DetailResponse(place.placeId(),place.name(),place.category(),place.regionLabel(),place.address(),
            place.roadAddress(),place.description(),place.latitude(),place.longitude()));
    }

    static String searchKey(String raw,int minimum,int maximum) {
        String value=displayText(raw,minimum,maximum).toLowerCase(Locale.ROOT);
        StringBuilder collapsed=new StringBuilder(value.length()); boolean whitespace=false;
        for (int offset=0;offset<value.length();) {
            int cp=value.codePointAt(offset); offset+=Character.charCount(cp);
            if (unicodeWhitespace(cp)) whitespace=true;
            else { if (whitespace && collapsed.length()>0) collapsed.append(' '); collapsed.appendCodePoint(cp); whitespace=false; }
        }
        return collapsed.toString();
    }
    private static String optionalNormalized(Map<String,String[]> parameters,String key) {
        String value=optional(parameters,key); return value==null?null:searchKey(value,1,100);
    }
    private static String displayText(String raw,int minimum,int maximum) {
        if(raw==null) throw invalid(); String value=Normalizer.normalize(raw,Normalizer.Form.NFC);
        int start=0,end=value.length();
        while(start<end&&unicodeWhitespace(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start));
        while(end>start&&unicodeWhitespace(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end));
        value=value.substring(start,end); long count=value.codePoints().count();
        if(count<minimum||count>maximum||value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static boolean unicodeWhitespace(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    private static Map<String,String[]> requireQueries(HttpServletRequest request,Set<String> allowed){
        Map<String,String[]> map=request.getParameterMap();
        for(var entry:map.entrySet()) if(!allowed.contains(entry.getKey())||entry.getValue().length!=1) throw invalid();
        return map;
    }
    private static String required(Map<String,String[]> map,String key){String value=optional(map,key);if(value==null)throw invalid();return value;}
    private static String optional(Map<String,String[]> map,String key){String[] values=map.get(key);return values==null?null:values[0];}
    private static int parseLimit(String value,boolean cursor){
        if(value==null)return cursor?-1:20;if(!value.matches("[0-9]+")||value.length()>2)throw invalid();
        try{int parsed=Integer.parseInt(value);if(parsed<1||parsed>50)throw invalid();return parsed;}catch(NumberFormatException e){throw invalid();}
    }
    private static UUID principal(Authentication auth){
        if(auth==null||!(auth.getPrincipal() instanceof AuthenticatedUser user))throw new PlaceCatalogException(401,"AUTHENTICATION_REQUIRED","Authentication is required");
        return user.userId();
    }
    private static SummaryResponse summary(PlaceCatalogService.Place p){return new SummaryResponse(p.placeId(),p.name(),p.category(),p.regionLabel(),p.address(),p.latitude(),p.longitude());}
    private static <T> ResponseEntity<T> privateJson(T body){return ResponseEntity.ok().header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(body);}
    private static PlaceCatalogException invalid(){return new PlaceCatalogException(400,"INVALID_REQUEST","The request is invalid");}
    private static PlaceCatalogException invalidCursor(){return new PlaceCatalogException(400,"INVALID_CURSOR","The cursor is invalid");}

    record SearchResponse(List<SummaryResponse> items,@JsonProperty("next_cursor") String nextCursor){}
    record SummaryResponse(@JsonProperty("place_id") UUID placeId,String name,String category,
        @JsonProperty("region_label") String regionLabel,String address,BigDecimal latitude,BigDecimal longitude){}
    record DetailResponse(@JsonProperty("place_id") UUID placeId,String name,String category,
        @JsonProperty("region_label") String regionLabel,String address,@JsonProperty("road_address") String roadAddress,
        String description,BigDecimal latitude,BigDecimal longitude){}
}
