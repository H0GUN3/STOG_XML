package com.stog.app.trip;

import androidx.lifecycle.SavedStateHandle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.auth.AuthApiClient;
import com.stog.app.auth.SecureRefreshStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PlaceViewModelRecoveryTest {
    private static final String TRIP="940e857d-b025-4f72-b420-2ee354a514ad", PLACE="4d0f73ea-43ab-43d7-9ca9-bc61b69f20db";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());
    @After public void clear() { store.clear(); }

    @Test public void ambiguousRetryKeepsRequestAndConflictRequiresNewExplicitIntentAtFreshVersion() throws Exception {
        try (Fixture fixture = new Fixture(); AuthApiClient client = signedIn(fixture)) {
            AtomicReference<TripViewModel> holder = new AtomicReference<>(); CountDownLatch trip = new CountDownLatch(1), place = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { TripViewModel model = new TripViewModel(client,new SavedStateHandle()); holder.set(model); model.detail().observeForever(value -> { if(value.kind()==TripViewModel.DetailKind.CONTENT) trip.countDown(); }); model.placeDetail().observeForever(value -> { if(value.kind()==TripViewModel.PlaceDetailKind.CONTENT) place.countDown(); }); model.select(TRIP); });
            assertTrue(trip.await(10,TimeUnit.SECONDS)); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> holder.get().selectPlace(PLACE)); assertTrue(place.await(10,TimeUnit.SECONDS));
            CountDownLatch retryable=observe(holder.get(),TripViewModel.PlaceSaveKind.RETRYABLE_ERROR); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> holder.get().saveSelectedPlace()); assertTrue(retryable.await(10,TimeUnit.SECONDS));
            CountDownLatch conflict=observe(holder.get(),TripViewModel.PlaceSaveKind.VERSION_CONFLICT), freshTrip=new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(()->holder.get().detail().observeForever(value->{if(value.kind()==TripViewModel.DetailKind.CONTENT&&value.trip().planVersion()==9)freshTrip.countDown();})); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> holder.get().saveSelectedPlace()); assertTrue(conflict.await(10,TimeUnit.SECONDS)); assertTrue(freshTrip.await(10,TimeUnit.SECONDS));
            CountDownLatch created=observe(holder.get(),TripViewModel.PlaceSaveKind.CREATED); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> holder.get().saveSelectedPlace()); assertTrue(created.await(10,TimeUnit.SECONDS));
            JSONObject first=new JSONObject(fixture.bodies.get(0)), second=new JSONObject(fixture.bodies.get(1)), third=new JSONObject(fixture.bodies.get(2));
            assertEquals(first.getString("request_id"),second.getString("request_id")); assertEquals(7,second.getLong("expected_plan_version")); assertNotEquals(second.getString("request_id"),third.getString("request_id")); assertEquals(9,third.getLong("expected_plan_version"));
        }
    }
    private static CountDownLatch observe(TripViewModel model, TripViewModel.PlaceSaveKind kind){CountDownLatch latch=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->model.placeSave().observeForever(value->{if(value.kind()==kind)latch.countDown();}));return latch;}
    private AuthApiClient signedIn(Fixture fixture)throws Exception{AuthApiClient client=new AuthApiClient(fixture.base(),store);CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();client.exchange("fixture",value->{ok.set(value);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,ok.get());return client;}

    private static final class Fixture implements AutoCloseable{
        final ServerSocket server=new ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"));final ExecutorService executor=Executors.newSingleThreadExecutor();final AtomicInteger saves=new AtomicInteger(),details=new AtomicInteger();final List<String>bodies=java.util.Collections.synchronizedList(new ArrayList<>());
        Fixture()throws IOException{executor.execute(this::serve);}URI base(){return URI.create("http://127.0.0.1:"+server.getLocalPort());}private void serve(){while(!server.isClosed())try(Socket socket=server.accept()){respond(socket);}catch(IOException ignored){}}
        private void respond(Socket socket)throws IOException{InputStream in=socket.getInputStream();String headers=headers(in),first=headers.substring(0,headers.indexOf("\r\n")),path=first.split(" ")[1];String body=new String(body(in,length(headers)),StandardCharsets.UTF_8);
            if(path.equals("/api/auth/kakao/exchange")){json(socket,200,"{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}");return;}
            if(path.equals("/api/trips/"+TRIP)){int n=details.incrementAndGet();json(socket,200,trip(n>1?9:7));return;}if(path.startsWith("/api/trips/"+TRIP+"/trip-items?")){json(socket,200,"{\"trip_id\":\""+TRIP+"\",\"plan_version\":"+(details.get()>1?9:7)+",\"items\":[],\"next_cursor\":null}");return;}
            if(path.equals("/api/places/"+PLACE)){json(socket,200,"{\"place_id\":\""+PLACE+"\",\"name\":\"성산일출봉\",\"category\":null,\"region_label\":null,\"address\":null,\"road_address\":null,\"description\":null,\"latitude\":null,\"longitude\":null}");return;}
            if(path.equals("/api/trips/"+TRIP+"/trip-items")&&first.startsWith("POST")){bodies.add(body);int n=saves.incrementAndGet();if(n==1)json(socket,503,error("DATABASE_OPERATION_FAILED"));else if(n==2)json(socket,409,error("PLAN_VERSION_CONFLICT"));else json(socket,201,"{\"result\":\"CREATED\",\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\""+PLACE+"\",\"plan_version\":10}");return;}json(socket,404,error("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE"));}
        private static String trip(long version){return "{\"trip_id\":\""+TRIP+"\",\"name\":\"trip\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\",\"as_of_date\":\"2026-10-06\",\"role\":\"ADMIN\",\"member_count\":1,\"plan_version\":"+version+",\"cover\":null}";}private static String error(String code){return "{\"error\":{\"code\":\""+code+"\",\"message\":\"safe\"}}";}
        private static void json(Socket socket,int status,String text)throws IOException{byte[]bytes=text.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();}private static int length(String h){String v=header(h,"content-length");return v==null?0:Integer.parseInt(v);}private static byte[]body(InputStream in,int length)throws IOException{byte[]v=new byte[length];for(int o=0;o<length;){int r=in.read(v,o,length-o);if(r<0)throw new IOException();o+=r;}return v;}private static String header(String h,String name){for(String line:h.split("\r\n")){int c=line.indexOf(':');if(c>0&&line.substring(0,c).toLowerCase(Locale.ROOT).equals(name))return line.substring(c+1).trim();}return null;}private static String headers(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]e={'\r','\n','\r','\n'};int m=0;while(m<4){int v=in.read();if(v<0)throw new IOException();out.write(v);m=v==e[m]?m+1:v==e[0]?1:0;}return out.toString(StandardCharsets.US_ASCII);}@Override public void close()throws Exception{server.close();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
