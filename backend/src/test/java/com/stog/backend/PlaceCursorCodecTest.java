package com.stog.backend;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceCursorCodecTest {
    private static final Instant NOW=Instant.parse("2026-10-06T00:00:00Z");
    private static final UUID USER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PLACE=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");

    @Test void legitimateMaximumUnicodeTupleFitsCorrectedBoundAndRoundTrips(){
        PlaceCursorCodec codec=codec();
        String query="😀".repeat(100),region="😀".repeat(100),category="😀".repeat(100),last="😀".repeat(200);
        String token=codec.encode(USER,50,query,region,category,last,PLACE);
        assertTrue(token.length()>1024,"old cap must be proven insufficient");
        assertTrue(token.length()<=PlaceCursorCodec.MAX_TOKEN_LENGTH);
        var decoded=codec.decode(token,USER,50,query,region,category);
        assertEquals(last,decoded.lastName());assertEquals(PLACE,decoded.lastPlaceId());
    }

    @Test void principalFilterLimitTamperAndOversizeAreRejected(){
        PlaceCursorCodec codec=codec();String token=codec.encode(USER,20,"제주",null,"카페","제주 카페",PLACE);
        invalid(()->codec.decode(token,OTHER,20,"제주",null,"카페"));
        invalid(()->codec.decode(token,USER,10,"제주",null,"카페"));
        invalid(()->codec.decode(token,USER,20,"서울",null,"카페"));
        int middle=token.length()/2;String changed=token.substring(0,middle)+(token.charAt(middle)=='A'?"B":"A")+token.substring(middle+1);
        invalid(()->codec.decode(changed,USER,20,"제주",null,"카페"));
        invalid(()->codec.decode("a".repeat(PlaceCursorCodec.MAX_TOKEN_LENGTH+1),USER,20,"제주",null,"카페"));
    }
    private PlaceCursorCodec codec(){return new PlaceCursorCodec(key,new SecureRandom(),Clock.fixed(NOW,ZoneOffset.UTC));}
    private static void invalid(Runnable action){PlaceCatalogException e=assertThrows(PlaceCatalogException.class,action::run);assertEquals("INVALID_CURSOR",e.code());}
}
