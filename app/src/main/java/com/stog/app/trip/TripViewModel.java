package com.stog.app.trip;

import androidx.annotation.NonNull;
import androidx.lifecycle.AbstractSavedStateViewModelFactory;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.savedstate.SavedStateRegistryOwner;

import com.stog.app.auth.AuthApiClient;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import android.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Credential-free owner for the R1 list, draft, pending create and selected trip. */
public final class TripViewModel extends ViewModel {
    public enum ListKind { IDLE, LOADING, CONTENT, EMPTY, ERROR, AUTH_ENDED }
    public enum DetailKind { IDLE, LOADING, CONTENT, ERROR, AUTH_ENDED }
    public enum CreateKind { IDLE, INVALID_INPUT, LOADING, RETRYABLE_ERROR, REQUEST_ID_REUSED, AUTH_ENDED }
    public enum MembersKind { IDLE, LOADING, CONTENT, EMPTY, ERROR, AUTH_ENDED }
    public enum CollaborationKind { IDLE, INVALID_INPUT, LOADING, SUCCESS, RETRYABLE_ERROR, VERSION_CONFLICT, DATE_RANGE_CONFLICT, ADMIN_REQUIRED, LAST_ADMIN_REQUIRED, INVITATION_UNUSABLE, AUTH_ENDED }
    public enum CoverKind { IDLE, PREVIEW, UPLOADING, CONTENT, MISSING, ERROR, PERMISSION_DENIED, VERSION_CONFLICT, AUTH_ENDED }
    public enum PlaceSearchKind { IDLE, INVALID_QUERY, LOADING, CONTENT, EMPTY, RETRYABLE_ERROR, AUTH_ENDED }
    public enum PlaceDetailKind { IDLE, LOADING, CONTENT, NOT_FOUND, RETRYABLE_ERROR, AUTH_ENDED }
    public enum PlaceSaveKind { IDLE, SAVING, CREATED, ALREADY_EXISTS, VERSION_CONFLICT, TRIP_ENDED, TRIP_INACCESSIBLE, RETRYABLE_ERROR, AUTH_ENDED }
    public record ListState(ListKind kind, List<AuthApiClient.Trip> trips, String nextCursor) {}
    public record DetailState(DetailKind kind, AuthApiClient.Trip trip, AuthApiClient.BasketPage basket) {}
    public record CreateState(CreateKind kind) {}
    public record MembersState(MembersKind kind, AuthApiClient.MemberPage page) {}
    /** No invitation token is kept in SavedState; this state contains only a display-safe outcome. */
    public record CollaborationState(CollaborationKind kind) {}
    public record CoverState(CoverKind kind, AuthApiClient.Cover cover, android.graphics.Bitmap preview) {}
    public record PlaceSearchState(PlaceSearchKind kind, List<AuthApiClient.Place> places, String nextCursor) {}
    public record PlaceDetailState(PlaceDetailKind kind, AuthApiClient.Place place) {}
    public record PlaceSaveState(PlaceSaveKind kind) {}
    public record EditDraft(String name, String regionLabel, String startDate, String endDate) {}

    private static final String DRAFT_NAME = "trip.draft.name";
    private static final String DRAFT_REGION = "trip.draft.region";
    private static final String DRAFT_START = "trip.draft.start";
    private static final String DRAFT_END = "trip.draft.end";
    private static final String PENDING_ID = "trip.pending.id";
    private static final String PENDING_NAME = "trip.pending.name";
    private static final String PENDING_REGION = "trip.pending.region";
    private static final String PENDING_START = "trip.pending.start";
    private static final String PENDING_END = "trip.pending.end";
    private static final String SELECTED_ID = "trip.selected.id";
    private static final String ANCHOR_ID = "trip.anchor.id";
    private static final String ANCHOR_OFFSET = "trip.anchor.offset";
    private static final String LOADED_PAGE_DEPTH = "trip.list.loaded_page_depth";
    private static final String EDIT_NAME = "trip.edit.name";
    private static final String EDIT_REGION = "trip.edit.region";
    private static final String EDIT_START = "trip.edit.start";
    private static final String EDIT_END = "trip.edit.end";
    private static final String EDIT_REQUEST_ID = "trip.edit.request_id";
    private static final String EDIT_KEY = "trip.edit.intent_key";
    private static final String PLACE_DRAFT_QUERY = "place.draft.query";
    private static final String PLACE_DRAFT_REGION = "place.draft.region";
    private static final String PLACE_DRAFT_CATEGORY = "place.draft.category";
    private static final String PLACE_QUERY = "place.query";
    private static final String PLACE_REGION = "place.region";
    private static final String PLACE_CATEGORY = "place.category";
    private static final String PLACE_SELECTED_ID = "place.selected.id";
    private static final String PLACE_ANCHOR_ID = "place.anchor.id";
    private static final String PLACE_ANCHOR_OFFSET = "place.anchor.offset";
    private static final String PLACE_PAGE_DEPTH = "place.page_depth";
    private static final int MAX_RESTORED_PAGE_DEPTH = 50;

    private final AuthApiClient api;
    private final SavedStateHandle saved;
    private final TripFlowState flow = new TripFlowState();
    private final MutableLiveData<String> displayName = new MutableLiveData<>();
    private final MutableLiveData<ListState> list = new MutableLiveData<>(new ListState(ListKind.IDLE, List.of(), null));
    private final MutableLiveData<DetailState> detail = new MutableLiveData<>(new DetailState(DetailKind.IDLE, null, null));
    private boolean createdTripPending;
    private final MutableLiveData<CreateState> create = new MutableLiveData<>(new CreateState(CreateKind.IDLE));
    private final MutableLiveData<MembersState> members = new MutableLiveData<>(new MembersState(MembersKind.IDLE, null));
    private final MutableLiveData<CollaborationState> collaboration = new MutableLiveData<>(new CollaborationState(CollaborationKind.IDLE));
    private final MutableLiveData<CoverState> cover = new MutableLiveData<>(new CoverState(CoverKind.IDLE, null, null));
    private final MutableLiveData<PlaceSearchState> placeSearch = new MutableLiveData<>(new PlaceSearchState(PlaceSearchKind.IDLE, List.of(), null));
    private final MutableLiveData<PlaceDetailState> placeDetail = new MutableLiveData<>(new PlaceDetailState(PlaceDetailKind.IDLE, null));
    private final MutableLiveData<PlaceSaveState> placeSave = new MutableLiveData<>(new PlaceSaveState(PlaceSaveKind.IDLE));
    private String selectedTripId;
    private long listGeneration;
    private long detailGeneration;
    private long createGeneration;
    private long placeGeneration;
    private boolean basketFreshRecoveryUsed;
    private int loadedPageDepth;
    private int restorationTargetDepth;
    private boolean listFreshRecoveryUsed;
    private EditDraft editDraft = new EditDraft("", "", "", "");
    private String editRequestId;
    private String editIntentKey;
    private String memberRequestId;
    private String memberActionKey;
    private String inviteToken;
    private String inviteRequestId;
    private String pendingShareUri;
    private String joinToken;
    private String joinRequestId;
    private String joinLabel;
    private CoverInput.Prepared pendingCover;
    private String coverRequestId;
    private String coverIntentKey;
    private String removeCoverRequestId;
    private long removeCoverVersion = -1;
    private String placeDraftQuery = "";
    private String placeDraftRegion = "";
    private String placeDraftCategory = "";
    private String submittedPlaceQuery;
    private String submittedPlaceRegion;
    private String submittedPlaceCategory;
    private String selectedPlaceId;
    private int loadedPlacePageDepth;
    private int restoredPlacePageDepth;
    private boolean placeCursorRecoveryUsed;
    private String placeSaveRequestId;
    private String placeSaveIntentKey;
    private static final SecureRandom INVITATION_RANDOM = new SecureRandom();

    TripViewModel(AuthApiClient api, SavedStateHandle saved) {
        this.api = api; this.saved = saved; restoreSmallState();
    }
    public LiveData<ListState> list() { return list; }
    public LiveData<String> displayName() { return displayName; }
    public void loadDisplayName() { if (api != null && displayName.getValue() == null) api.currentDisplayName(displayName::setValue); }
    public LiveData<DetailState> detail() { return detail; }
    public LiveData<CreateState> create() { return create; }
    public LiveData<MembersState> members() { return members; }
    public LiveData<CollaborationState> collaboration() { return collaboration; }
    public LiveData<CoverState> cover() { return cover; }
    public LiveData<PlaceSearchState> placeSearch() { return placeSearch; }
    public LiveData<PlaceDetailState> placeDetail() { return placeDetail; }
    public LiveData<PlaceSaveState> placeSave() { return placeSave; }
    public EditDraft editDraft() { return editDraft; }
    public boolean hasPendingJoin() { return joinToken != null; }
    /** Consumed only to populate ACTION_SEND; never saved or rendered. */
    public String consumePendingShareUri() { String uri = pendingShareUri; pendingShareUri = null; return uri; }
    public TripFlowState flow() { return flow; }
    public String selectedTripId() { return selectedTripId; }
    public String anchorTripId() { return saved.get(ANCHOR_ID); }
    public int anchorOffset() { Integer value = saved.get(ANCHOR_OFFSET); return value == null ? 0 : value; }
    public void saveAnchor(String tripId, int offset) { saved.set(ANCHOR_ID, tripId); saved.set(ANCHOR_OFFSET, offset); }
    public void persistDraft() { saveFlow(); }
    public String placeDraftQuery() { return placeDraftQuery; }
    public String placeDraftRegion() { return placeDraftRegion; }
    public String placeDraftCategory() { return placeDraftCategory; }
    public String placeAnchorId() { return saved.get(PLACE_ANCHOR_ID); }
    public int placeAnchorOffset() { Integer value = saved.get(PLACE_ANCHOR_OFFSET); return value == null ? 0 : value; }
    public void savePlaceAnchor(String id, int offset) { saved.set(PLACE_ANCHOR_ID, id); saved.set(PLACE_ANCHOR_OFFSET, offset); }
    public void updatePlaceDraft(String query, String region, String category) { placeDraftQuery = value(query); placeDraftRegion = value(region); placeDraftCategory = value(category); savePlaceSmallState(); }

    public void loadTrips() {
        final long request = ++listGeneration;
        if (api == null) { list.setValue(new ListState(ListKind.ERROR, List.of(), null)); return; }
        loadedPageDepth = 0;
        list.setValue(new ListState(ListKind.LOADING, List.of(), null));
        api.currentTrips(result -> {
            if (request != listGeneration) return;
            if (result.outcome() == AuthApiClient.TripOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() != AuthApiClient.TripOutcome.SUCCESS) { list.setValue(new ListState(ListKind.ERROR, List.of(), null)); return; }
            loadedPageDepth = 1;
            saveLoadedPageDepth();
            List<AuthApiClient.Trip> trips = List.copyOf(result.page().items());
            list.setValue(new ListState(trips.isEmpty() ? ListKind.EMPTY : ListKind.CONTENT, trips, result.page().nextCursor()));
            continueRestorationIfNeeded();
        });
    }

    public void loadNextTrips() { loadNextTrips(false); }

    private void loadNextTrips(boolean restoring) {
        ListState current = list.getValue();
        if (api == null || current == null || current.nextCursor() == null || current.kind() == ListKind.LOADING) return;
        final long request = ++listGeneration;
        List<AuthApiClient.Trip> prior = current.trips();
        list.setValue(new ListState(ListKind.LOADING, prior, current.nextCursor()));
        api.currentTrips(current.nextCursor(), result -> {
            if (request != listGeneration) return;
            if (result.outcome() == AuthApiClient.TripOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.TripOutcome.INVALID_CURSOR) {
                if (!restoring) {
                    loadTrips();
                } else if (!listFreshRecoveryUsed) {
                    listFreshRecoveryUsed = true;
                    loadTrips();
                } else {
                    restorationTargetDepth = 0;
                    saveLoadedPageDepth();
                    list.setValue(new ListState(ListKind.ERROR, prior, current.nextCursor()));
                }
                return;
            }
            if (result.outcome() != AuthApiClient.TripOutcome.SUCCESS) { list.setValue(new ListState(ListKind.ERROR, prior, current.nextCursor())); return; }
            ArrayList<AuthApiClient.Trip> combined = new ArrayList<>(prior);
            combined.addAll(result.page().items());
            loadedPageDepth++;
            saveLoadedPageDepth();
            list.setValue(new ListState(combined.isEmpty() ? ListKind.EMPTY : ListKind.CONTENT, List.copyOf(combined), result.page().nextCursor()));
            if (restoring) continueRestorationIfNeeded();
        });
    }

    private void continueRestorationIfNeeded() {
        ListState current = list.getValue();
        if (restorationTargetDepth <= loadedPageDepth || current == null || current.nextCursor() == null) {
            restorationTargetDepth = 0;
            listFreshRecoveryUsed = false;
            saveLoadedPageDepth();
            return;
        }
        loadNextTrips(true);
    }

    private void saveLoadedPageDepth() {
        int depth = Math.max(loadedPageDepth, restorationTargetDepth);
        saved.set(LOADED_PAGE_DEPTH, Math.max(1, Math.min(depth, MAX_RESTORED_PAGE_DEPTH)));
    }

    /** True once after a create succeeds, so a trip opened earlier never reads as the new one. */
    public boolean consumeCreatedTrip() {
        boolean created = createdTripPending;
        createdTripPending = false;
        return created;
    }

    public void submitCreate() {
        TripFlowState.Pending pending = flow.pendingForSubmit();
        saveFlow();
        if (!valid(pending.normalizedDraft())) { create.setValue(new CreateState(CreateKind.INVALID_INPUT)); return; }
        if (api == null) { create.setValue(new CreateState(CreateKind.RETRYABLE_ERROR)); return; }
        final long request = ++createGeneration;
        create.setValue(new CreateState(CreateKind.LOADING));
        api.createTrip(new AuthApiClient.TripCreateRequest(pending.normalizedDraft().name(), pending.normalizedDraft().regionLabel(),
            pending.normalizedDraft().startDate(), pending.normalizedDraft().endDate(), pending.requestId()), result -> {
            if (request != createGeneration) return;
            switch (result.outcome()) {
                case SUCCESS -> {
                    flow.clearAfterDefinitiveEnd(); saveFlow(); selectedTripId = result.trip().tripId(); saved.set(SELECTED_ID, selectedTripId);
                    createdTripPending = true; create.setValue(new CreateState(CreateKind.IDLE)); detail.setValue(new DetailState(DetailKind.CONTENT, result.trip(), null)); loadTrips();
                }
                case INVALID_REQUEST -> { flow.clearAfterDefinitiveEnd(); saveFlow(); create.setValue(new CreateState(CreateKind.INVALID_INPUT)); }
                case REQUEST_ID_REUSED -> create.setValue(new CreateState(CreateKind.REQUEST_ID_REUSED));
                case AUTH_ENDED -> authEnded();
                default -> create.setValue(new CreateState(CreateKind.RETRYABLE_ERROR));
            }
        });
    }

    public void select(String tripId) {
        basketFreshRecoveryUsed = false;
        if (!java.util.Objects.equals(selectedTripId, tripId)) clearPlaceFlow();
        selectedTripId = tripId; saved.set(SELECTED_ID, tripId); loadDetail();
    }

    public void restoreOrSearchPlaces() {
        if (placeSearch.getValue() != null && placeSearch.getValue().kind() != PlaceSearchKind.IDLE) return;
        if (submittedPlaceQuery != null) searchPlacesInternal(null, true); 
    }

    public void submitPlaceSearch() {
        String query = normalizeSearch(placeDraftQuery); String region = optionalSearch(placeDraftRegion); String category = optionalSearch(placeDraftCategory);
        int count = query.codePointCount(0, query.length());
        if (count < 2 || count > 100 || hasIsoControl(query) || (region != null && (region.codePointCount(0, region.length()) > 100 || hasIsoControl(region))) || (category != null && (category.codePointCount(0, category.length()) > 100 || hasIsoControl(category)))) {
            placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.INVALID_QUERY, List.of(), null)); return;
        }
        submittedPlaceQuery = query; submittedPlaceRegion = region; submittedPlaceCategory = category;
        selectedPlaceId = null; loadedPlacePageDepth = 0; restoredPlacePageDepth = 0; placeCursorRecoveryUsed = false;
        saved.set(PLACE_SELECTED_ID, null); saved.set(PLACE_ANCHOR_ID, null); saved.set(PLACE_ANCHOR_OFFSET, null); savePlaceSmallState();
        searchPlacesInternal(null, false);
    }

    public void loadNextPlaces() {
        PlaceSearchState current = placeSearch.getValue();
        if (current == null || current.nextCursor() == null || current.kind() == PlaceSearchKind.LOADING) return;
        searchPlacesInternal(current.nextCursor(), false);
    }

    private void searchPlacesInternal(String cursor, boolean restoring) {
        if (api == null || submittedPlaceQuery == null) { placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.RETRYABLE_ERROR, List.of(), null)); return; }
        final long request = ++placeGeneration; final List<AuthApiClient.Place> prior = cursor == null ? List.of() : placeSearch.getValue().places();
        placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.LOADING, prior, cursor));
        api.searchPlaces(submittedPlaceQuery, submittedPlaceRegion, submittedPlaceCategory, cursor, result -> {
            if (request != placeGeneration) return;
            if (result.outcome() == AuthApiClient.PlaceOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.PlaceOutcome.INVALID_CURSOR && !placeCursorRecoveryUsed) { placeCursorRecoveryUsed = true; loadedPlacePageDepth = 0; searchPlacesInternal(null, restoring); return; }
            if (result.outcome() != AuthApiClient.PlaceOutcome.SUCCESS) { placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.RETRYABLE_ERROR, prior, cursor)); return; }
            ArrayList<AuthApiClient.Place> combined = new ArrayList<>(prior); combined.addAll(result.page().items()); loadedPlacePageDepth++;
            saved.set(PLACE_PAGE_DEPTH, Math.max(1, Math.min(Math.max(loadedPlacePageDepth, restoredPlacePageDepth), MAX_RESTORED_PAGE_DEPTH)));
            PlaceSearchKind kind = combined.isEmpty() ? PlaceSearchKind.EMPTY : PlaceSearchKind.CONTENT;
            placeSearch.setValue(new PlaceSearchState(kind, List.copyOf(combined), result.page().nextCursor()));
            if (restoring && loadedPlacePageDepth < restoredPlacePageDepth && result.page().nextCursor() != null) searchPlacesInternal(result.page().nextCursor(), true);
            else if (restoring) { restoredPlacePageDepth = 0; placeCursorRecoveryUsed = false; }
        });
    }

    public void startPlaceSearchWithoutTrip() { clearAffectedTrip(); }
    public void selectPlace(String placeId) { selectedPlaceId = placeId; saved.set(PLACE_SELECTED_ID, placeId); loadPlaceDetail(); }
    public void loadPlaceDetail() {
        if (api == null || selectedPlaceId == null) return;
        final String id = selectedPlaceId; final long request = ++placeGeneration; placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.LOADING, null));
        api.placeDetail(id, result -> {
            if (request != placeGeneration || !id.equals(selectedPlaceId)) return;
            switch (result.outcome()) {
                case SUCCESS -> placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.CONTENT, result.place()));
                case NOT_FOUND -> placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.NOT_FOUND, null));
                case AUTH_ENDED -> authEnded();
                default -> placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.RETRYABLE_ERROR, null));
            }
        });
    }

    public void saveSelectedPlace() {
        DetailState tripState = detail.getValue();
        if (api == null || selectedTripId == null || selectedPlaceId == null || tripState == null || tripState.trip() == null) { placeSave.setValue(new PlaceSaveState(PlaceSaveKind.RETRYABLE_ERROR)); return; }
        long version = tripState.trip().planVersion(); String key = selectedTripId + "\u0000" + selectedPlaceId + "\u0000" + version;
        if (!key.equals(placeSaveIntentKey)) { placeSaveIntentKey = key; placeSaveRequestId = java.util.UUID.randomUUID().toString(); }
        final long request = ++placeGeneration; final String tripId = selectedTripId; final String placeId = selectedPlaceId;
        placeSave.setValue(new PlaceSaveState(PlaceSaveKind.SAVING));
        api.savePlaceToBasket(tripId, placeId, version, placeSaveRequestId, result -> {
            if (request != placeGeneration || !tripId.equals(selectedTripId) || !placeId.equals(selectedPlaceId)) return;
            switch (result.outcome()) {
                case CREATED -> { placeSaveIntentKey = null; placeSaveRequestId = null; placeSave.setValue(new PlaceSaveState(PlaceSaveKind.CREATED)); loadDetail(); }
                case ALREADY_EXISTS -> { placeSaveIntentKey = null; placeSaveRequestId = null; placeSave.setValue(new PlaceSaveState(PlaceSaveKind.ALREADY_EXISTS)); loadDetail(); }
                case VERSION_CONFLICT -> { placeSaveIntentKey = null; placeSaveRequestId = null; placeSave.setValue(new PlaceSaveState(PlaceSaveKind.VERSION_CONFLICT)); loadDetail(); }
                case TRIP_ENDED -> { placeSaveIntentKey = null; placeSaveRequestId = null; placeSave.setValue(new PlaceSaveState(PlaceSaveKind.TRIP_ENDED)); }
                case TRIP_INACCESSIBLE -> { placeSaveIntentKey = null; placeSaveRequestId = null; clearAffectedTrip(); placeSave.setValue(new PlaceSaveState(PlaceSaveKind.TRIP_INACCESSIBLE)); }
                case AUTH_ENDED -> authEnded();
                default -> placeSave.setValue(new PlaceSaveState(PlaceSaveKind.RETRYABLE_ERROR));
            }
        });
    }

    public void clearPlaceFlow() {
        ++placeGeneration; if (api != null) api.cancelPlaceRequests();
        submittedPlaceQuery = null; submittedPlaceRegion = null; submittedPlaceCategory = null; selectedPlaceId = null;
        loadedPlacePageDepth = 0; restoredPlacePageDepth = 0; placeCursorRecoveryUsed = false; placeSaveRequestId = null; placeSaveIntentKey = null;
        saved.set(PLACE_QUERY, null); saved.set(PLACE_REGION, null); saved.set(PLACE_CATEGORY, null); saved.set(PLACE_SELECTED_ID, null); saved.set(PLACE_ANCHOR_ID, null); saved.set(PLACE_ANCHOR_OFFSET, null); saved.set(PLACE_PAGE_DEPTH, null);
        placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.IDLE, List.of(), null)); placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.IDLE, null)); placeSave.setValue(new PlaceSaveState(PlaceSaveKind.IDLE));
    }

    public void beginEdit() {
        DetailState current = detail.getValue();
        if (current == null || current.trip() == null) return;
        AuthApiClient.Trip trip = current.trip();
        if (editRequestId == null && editDraft.name().isEmpty() && editDraft.regionLabel().isEmpty()) {
            editDraft = new EditDraft(value(trip.name()), value(trip.regionLabel()), trip.startDate(), trip.endDate()); saveEdit();
        }
    }
    public void updateEditDraft(String name, String region, String start, String end) { editDraft = new EditDraft(name, region, start, end); saveEdit(); }
    public void submitEdit() {
        DetailState current = detail.getValue();
        if (api == null || current == null || current.trip() == null) { collaboration.setValue(new CollaborationState(CollaborationKind.RETRYABLE_ERROR)); return; }
        EditDraft normalized = normalize(editDraft);
        if (!valid(new TripFlowState.Draft(normalized.name(), normalized.regionLabel(), normalized.startDate(), normalized.endDate()))) { collaboration.setValue(new CollaborationState(CollaborationKind.INVALID_INPUT)); return; }
        String key = normalized.name() + "\u0000" + normalized.regionLabel() + "\u0000" + normalized.startDate() + "\u0000" + normalized.endDate() + "\u0000" + current.trip().planVersion();
        if (!key.equals(editIntentKey)) { editRequestId = java.util.UUID.randomUUID().toString(); editIntentKey = key; saveEdit(); }
        collaboration.setValue(new CollaborationState(CollaborationKind.LOADING));
        api.updateTrip(current.trip().tripId(), new AuthApiClient.TripUpdateRequest(normalized.name(), normalized.regionLabel(), normalized.startDate(), normalized.endDate(), current.trip().planVersion(), editRequestId), result -> handleEdit(result, current.trip().tripId()));
    }
    private void handleEdit(AuthApiClient.CollaborationResult result, String tripId) {
        if (result.outcome() == AuthApiClient.CollaborationOutcome.AUTH_ENDED) { authEnded(); return; }
        if (result.outcome() == AuthApiClient.CollaborationOutcome.SUCCESS && result.trip() != null) {
            editIntentKey = null; editRequestId = null; editDraft = new EditDraft("", "", "", ""); saveEdit(); detail.setValue(new DetailState(DetailKind.CONTENT, result.trip(), detail.getValue() == null ? null : detail.getValue().basket())); collaboration.setValue(new CollaborationState(CollaborationKind.SUCCESS)); return;
        }
        applyCollaborationFailure(result, true);
    }
    public void loadMembers() {
        if (api == null || selectedTripId == null) return;
        members.setValue(new MembersState(MembersKind.LOADING, members.getValue() == null ? null : members.getValue().page()));
        api.members(selectedTripId, null, result -> {
            if (result.outcome() == AuthApiClient.CollaborationOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.SUCCESS && result.members() != null) {
                members.setValue(new MembersState(result.members().items().isEmpty() ? MembersKind.EMPTY : MembersKind.CONTENT, result.members())); return;
            }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.VERSION_CONFLICT || result.outcome() == AuthApiClient.CollaborationOutcome.INVALID_CURSOR) { loadMembers(); return; }
            members.setValue(new MembersState(MembersKind.ERROR, null)); applyCollaborationFailure(result, false);
        });
    }
    public void changeRole(AuthApiClient.Member member, String newRole) { memberCommand("role\u0000" + member.memberUserId() + "\u0000" + newRole, callback -> api.updateMemberRole(selectedTripId, member.memberUserId(), newRole, memberVersion(), memberRequestId, callback), false); }
    public void removeMember(AuthApiClient.Member member) { memberCommand("remove\u0000" + member.memberUserId(), callback -> api.removeMember(selectedTripId, member.memberUserId(), memberVersion(), memberRequestId, callback), member.self()); }
    public void leaveTrip() { memberCommand("leave", callback -> api.leaveTrip(selectedTripId, currentVersion(), memberRequestId, callback), true); }
    public void deleteTrip() { memberCommand("delete", callback -> api.deleteTrip(selectedTripId, currentVersion(), memberRequestId, callback), true); }
    private interface CollaborationCall { void call(AuthApiClient.CollaborationCallback callback); }
    private void memberCommand(String key, CollaborationCall call, boolean clearsTrip) {
        if (api == null || selectedTripId == null || currentVersion() < 0) return;
        if (!key.equals(memberActionKey)) { memberActionKey = key; memberRequestId = java.util.UUID.randomUUID().toString(); }
        collaboration.setValue(new CollaborationState(CollaborationKind.LOADING));
        call.call(result -> {
            if (result.outcome() == AuthApiClient.CollaborationOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.SUCCESS) {
                memberActionKey = null; memberRequestId = null; collaboration.setValue(new CollaborationState(CollaborationKind.SUCCESS));
                if (clearsTrip) clearAffectedTrip(); else { loadMembers(); loadDetail(); }
            } else applyCollaborationFailure(result, true);
        });
    }
    public void shareInvitation() {
        if (api == null || selectedTripId == null) return;
        if (inviteToken == null) { byte[] bytes = new byte[32]; INVITATION_RANDOM.nextBytes(bytes); inviteToken = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); inviteRequestId = java.util.UUID.randomUUID().toString(); }
        collaboration.setValue(new CollaborationState(CollaborationKind.LOADING));
        api.issueInvitation(selectedTripId, inviteToken, inviteRequestId, result -> {
            if (result.outcome() == AuthApiClient.CollaborationOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.SUCCESS && result.inviteUri() != null) { pendingShareUri = result.inviteUri(); collaboration.setValue(new CollaborationState(CollaborationKind.SUCCESS)); return; }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.REQUEST_ID_REUSED) { inviteToken = null; inviteRequestId = null; }
            applyCollaborationFailure(result, false);
        });
    }
    public void invitationShared() { inviteToken = null; inviteRequestId = null; }
    void coverPermissionDenied() { cover.setValue(new CoverState(CoverKind.PERMISSION_DENIED, null, null)); }
    void selectCover(CoverInput.Prepared input) {
        discardPendingCover(); pendingCover = input;
        coverIntentKey = input.sha256() + "\u0000" + input.mediaType(); coverRequestId = java.util.UUID.randomUUID().toString();
        cover.setValue(new CoverState(CoverKind.PREVIEW, null, input.preview()));
    }
    void submitCover() {
        DetailState current = detail.getValue();
        if (api == null || pendingCover == null || current == null || current.trip() == null) { cover.setValue(new CoverState(CoverKind.ERROR, null, null)); return; }
        if (coverRequestId == null) coverRequestId = java.util.UUID.randomUUID().toString();
        cover.setValue(new CoverState(CoverKind.UPLOADING, null, pendingCover.preview()));
        String tripId = current.trip().tripId();
        api.setTripCover(tripId, new AuthApiClient.CoverUpload(pendingCover.file(), pendingCover.mediaType(), current.trip().planVersion(), coverRequestId), result -> {
            if (!tripId.equals(selectedTripId)) return;
            if (result.outcome() == AuthApiClient.CoverOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CoverOutcome.SUCCESS) { discardPendingCover(); coverIntentKey = null; coverRequestId = null; cover.setValue(new CoverState(CoverKind.CONTENT, result.cover(), null)); markListStale(); loadDetail(); return; }
            if (result.outcome() == AuthApiClient.CoverOutcome.VERSION_CONFLICT) coverRequestId = null;
            cover.setValue(new CoverState(result.outcome() == AuthApiClient.CoverOutcome.VERSION_CONFLICT ? CoverKind.VERSION_CONFLICT : result.outcome() == AuthApiClient.CoverOutcome.MISSING ? CoverKind.MISSING : CoverKind.ERROR, null, pendingCover == null ? null : pendingCover.preview()));
            if (result.outcome() == AuthApiClient.CoverOutcome.VERSION_CONFLICT) loadDetail();
        });
    }
    /** Cards read covers from the list, so the next list screen reloads it. Reading now would cancel the detail read. */
    private void markListStale() { ++listGeneration; list.setValue(new ListState(ListKind.IDLE, List.of(), null)); }

    public void removeCover() {
        DetailState current = detail.getValue();
        if (api == null || current == null || current.trip() == null) return;
        if (removeCoverRequestId == null || removeCoverVersion != current.trip().planVersion()) { removeCoverRequestId = java.util.UUID.randomUUID().toString(); removeCoverVersion = current.trip().planVersion(); }
        String tripId = current.trip().tripId(); cover.setValue(new CoverState(CoverKind.UPLOADING, current.trip().cover(), null));
        api.removeTripCover(tripId, current.trip().planVersion(), removeCoverRequestId, result -> {
            if (!tripId.equals(selectedTripId)) return;
            if (result.outcome() == AuthApiClient.CoverOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CoverOutcome.SUCCESS) { removeCoverRequestId = null; removeCoverVersion = -1; cover.setValue(new CoverState(CoverKind.MISSING, null, null)); markListStale(); loadDetail(); return; }
            cover.setValue(new CoverState(result.outcome() == AuthApiClient.CoverOutcome.VERSION_CONFLICT ? CoverKind.VERSION_CONFLICT : CoverKind.ERROR, null, null)); if (result.outcome() == AuthApiClient.CoverOutcome.VERSION_CONFLICT) loadDetail();
        });
    }
    public void loadCoverImage(AuthApiClient.Cover value, boolean thumbnail, AuthApiClient.CoverImageCallback callback) { if (api != null && value != null) api.coverImage(value, thumbnail, callback); }
    public void stopInvitation() { memberCommand("stop-invitation", callback -> api.revokeInvitation(selectedTripId, memberRequestId, callback), false); }
    public boolean prepareJoin(String raw) { joinToken = InvitationInput.parse(raw); joinRequestId = null; joinLabel = null; collaboration.setValue(new CollaborationState(joinToken == null ? CollaborationKind.INVALID_INPUT : CollaborationKind.IDLE)); return joinToken != null; }
    public void submitJoin(String rawLabel) {
        if (api == null || joinToken == null) { collaboration.setValue(new CollaborationState(CollaborationKind.INVALID_INPUT)); return; }
        String label = normalizeText(rawLabel); if (label.isBlank() || label.codePointCount(0, label.length()) > 40) { collaboration.setValue(new CollaborationState(CollaborationKind.INVALID_INPUT)); return; }
        if (!label.equals(joinLabel)) { joinLabel = label; joinRequestId = java.util.UUID.randomUUID().toString(); }
        collaboration.setValue(new CollaborationState(CollaborationKind.LOADING));
        api.joinInvitation(joinToken, label, joinRequestId, result -> {
            if (result.outcome() == AuthApiClient.CollaborationOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() == AuthApiClient.CollaborationOutcome.SUCCESS) { joinToken = null; joinRequestId = null; joinLabel = null; collaboration.setValue(new CollaborationState(CollaborationKind.SUCCESS)); loadTrips(); return; }
            applyCollaborationFailure(result, false);
        });
    }
    private long memberVersion() { MembersState state = members.getValue(); return state != null && state.page() != null ? state.page().planVersion() : currentVersion(); }
    private long currentVersion() { DetailState state = detail.getValue(); return state != null && state.trip() != null ? state.trip().planVersion() : -1; }
    private void clearAffectedTrip() { clearPlaceFlow(); selectedTripId = null; saved.set(SELECTED_ID, null); saved.set(ANCHOR_ID, null); saved.set(ANCHOR_OFFSET, null); detail.setValue(new DetailState(DetailKind.IDLE, null, null)); members.setValue(new MembersState(MembersKind.IDLE, null)); loadTrips(); }
    private void applyCollaborationFailure(AuthApiClient.CollaborationResult result, boolean reloadOnConflict) {
        CollaborationKind kind = switch (result.outcome()) {
            case VERSION_CONFLICT -> CollaborationKind.VERSION_CONFLICT; case DATE_RANGE_CONFLICT -> CollaborationKind.DATE_RANGE_CONFLICT;
            case ADMIN_REQUIRED, ADMIN_CANNOT_LEAVE, FORBIDDEN -> CollaborationKind.ADMIN_REQUIRED; case LAST_ADMIN_REQUIRED -> CollaborationKind.LAST_ADMIN_REQUIRED;
            case INVITATION_UNUSABLE -> CollaborationKind.INVITATION_UNUSABLE; case AUTH_ENDED -> CollaborationKind.AUTH_ENDED; default -> CollaborationKind.RETRYABLE_ERROR;
        };
        collaboration.setValue(new CollaborationState(kind));
        if (reloadOnConflict && (result.outcome() == AuthApiClient.CollaborationOutcome.VERSION_CONFLICT || result.outcome() == AuthApiClient.CollaborationOutcome.TRIP_NOT_FOUND)) { loadDetail(); loadMembers(); }
    }
    public void loadDetail() {
        if (selectedTripId == null || api == null) return;
        final String selected = selectedTripId;
        final long request = ++detailGeneration;
        detail.setValue(new DetailState(DetailKind.LOADING, null, null));
        api.tripDetail(selected, result -> {
            if (request != detailGeneration || !selected.equals(selectedTripId)) return;
            if (result.outcome() == AuthApiClient.TripOutcome.AUTH_ENDED) { authEnded(); return; }
            if (result.outcome() != AuthApiClient.TripOutcome.SUCCESS) { detail.setValue(new DetailState(DetailKind.ERROR, null, null)); return; }
            AuthApiClient.Trip trip = result.trip();
            api.tripBasket(trip.tripId(), basket -> {
                if (request != detailGeneration || !selected.equals(selectedTripId)) return;
                if (basket.outcome() == AuthApiClient.BasketOutcome.AUTH_ENDED) { authEnded(); return; }
                if ((basket.outcome() == AuthApiClient.BasketOutcome.INVALID_CURSOR
                        || basket.outcome() == AuthApiClient.BasketOutcome.VERSION_CONFLICT) && !basketFreshRecoveryUsed) {
                    basketFreshRecoveryUsed = true;
                    loadDetail();
                    return;
                }
                if (basket.outcome() == AuthApiClient.BasketOutcome.SUCCESS) {
                    basketFreshRecoveryUsed = false;
                    detail.setValue(new DetailState(DetailKind.CONTENT, trip, basket.page()));
                } else detail.setValue(new DetailState(DetailKind.ERROR, trip, null));
            });
        });
    }

    public void authEnded() {
        displayName.setValue(null);
        ++listGeneration; ++detailGeneration; ++createGeneration; ++placeGeneration;
        if (api != null) { api.cancelCoverRequests(); api.cancelPlaceRequests(); } discardPendingCover(); coverRequestId = null; coverIntentKey = null; removeCoverRequestId = null; removeCoverVersion = -1;
        joinToken = null; joinRequestId = null; joinLabel = null; inviteToken = null; inviteRequestId = null; pendingShareUri = null; editIntentKey = null; memberRequestId = null; memberActionKey = null;
        basketFreshRecoveryUsed = false;
        loadedPageDepth = 0; restorationTargetDepth = 0; listFreshRecoveryUsed = false;
        flow.authEnded(); saveFlow(); selectedTripId = null; saved.set(SELECTED_ID, null);
        saved.set(ANCHOR_ID, null); saved.set(ANCHOR_OFFSET, null); saved.set(LOADED_PAGE_DEPTH, null);
        editDraft = new EditDraft("", "", "", ""); editRequestId = null; saved.set(EDIT_NAME, null); saved.set(EDIT_REGION, null); saved.set(EDIT_START, null); saved.set(EDIT_END, null); saved.set(EDIT_REQUEST_ID, null); saved.set(EDIT_KEY, null);
        list.setValue(new ListState(ListKind.AUTH_ENDED, List.of(), null));
        detail.setValue(new DetailState(DetailKind.AUTH_ENDED, null, null));
        create.setValue(new CreateState(CreateKind.AUTH_ENDED));
        members.setValue(new MembersState(MembersKind.AUTH_ENDED, null));
        collaboration.setValue(new CollaborationState(CollaborationKind.AUTH_ENDED));
        cover.setValue(new CoverState(CoverKind.AUTH_ENDED, null, null));
        clearPlaceSavedState();
        placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.AUTH_ENDED, List.of(), null));
        placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.AUTH_ENDED, null));
        placeSave.setValue(new PlaceSaveState(PlaceSaveKind.AUTH_ENDED));
    }

    /** A new sign-in starts clean: screens must not keep showing the previous session's ended state. */
    public void clearEndedSession() {
        displayName.setValue(null);
        if (list.getValue() != null && list.getValue().kind() == ListKind.AUTH_ENDED) list.setValue(new ListState(ListKind.IDLE, List.of(), null));
        if (detail.getValue() != null && detail.getValue().kind() == DetailKind.AUTH_ENDED) detail.setValue(new DetailState(DetailKind.IDLE, null, null));
        if (create.getValue() != null && create.getValue().kind() == CreateKind.AUTH_ENDED) create.setValue(new CreateState(CreateKind.IDLE));
        if (members.getValue() != null && members.getValue().kind() == MembersKind.AUTH_ENDED) members.setValue(new MembersState(MembersKind.IDLE, null));
        if (collaboration.getValue() != null && collaboration.getValue().kind() == CollaborationKind.AUTH_ENDED) collaboration.setValue(new CollaborationState(CollaborationKind.IDLE));
        if (cover.getValue() != null && cover.getValue().kind() == CoverKind.AUTH_ENDED) cover.setValue(new CoverState(CoverKind.IDLE, null, null));
        if (placeSearch.getValue() != null && placeSearch.getValue().kind() == PlaceSearchKind.AUTH_ENDED) placeSearch.setValue(new PlaceSearchState(PlaceSearchKind.IDLE, List.of(), null));
        if (placeDetail.getValue() != null && placeDetail.getValue().kind() == PlaceDetailKind.AUTH_ENDED) placeDetail.setValue(new PlaceDetailState(PlaceDetailKind.IDLE, null));
        if (placeSave.getValue() != null && placeSave.getValue().kind() == PlaceSaveKind.AUTH_ENDED) placeSave.setValue(new PlaceSaveState(PlaceSaveKind.IDLE));
    }

    private void discardPendingCover() { if (pendingCover != null) { if (pendingCover.preview() != null && !pendingCover.preview().isRecycled()) pendingCover.preview().recycle(); pendingCover.file().delete(); pendingCover = null; } }
    @Override protected void onCleared() { if (api != null) { api.cancelCoverRequests(); api.cancelPlaceRequests(); } discardPendingCover(); super.onCleared(); }
    private void restoreSmallState() {
        flow.restore(new TripFlowState.Saved(new TripFlowState.Draft(string(DRAFT_NAME), string(DRAFT_REGION), string(DRAFT_START), string(DRAFT_END)),
            saved.get(PENDING_ID) == null ? null : new TripFlowState.Pending(new TripFlowState.Draft(string(PENDING_NAME), string(PENDING_REGION), string(PENDING_START), string(PENDING_END)), saved.get(PENDING_ID))));
        selectedTripId = saved.get(SELECTED_ID);
        editDraft = new EditDraft(string(EDIT_NAME), string(EDIT_REGION), string(EDIT_START), string(EDIT_END));
        editRequestId = saved.get(EDIT_REQUEST_ID); editIntentKey = saved.get(EDIT_KEY);
        Integer depth = saved.get(LOADED_PAGE_DEPTH);
        restorationTargetDepth = depth == null ? 0 : Math.max(0, Math.min(depth, MAX_RESTORED_PAGE_DEPTH));
        placeDraftQuery = string(PLACE_DRAFT_QUERY); placeDraftRegion = string(PLACE_DRAFT_REGION); placeDraftCategory = string(PLACE_DRAFT_CATEGORY);
        submittedPlaceQuery = saved.get(PLACE_QUERY); submittedPlaceRegion = saved.get(PLACE_REGION); submittedPlaceCategory = saved.get(PLACE_CATEGORY); selectedPlaceId = saved.get(PLACE_SELECTED_ID);
        Integer placeDepth = saved.get(PLACE_PAGE_DEPTH); restoredPlacePageDepth = placeDepth == null ? 0 : Math.max(0, Math.min(placeDepth, MAX_RESTORED_PAGE_DEPTH));
    }
    private void savePlaceSmallState() {
        saved.set(PLACE_DRAFT_QUERY, placeDraftQuery); saved.set(PLACE_DRAFT_REGION, placeDraftRegion); saved.set(PLACE_DRAFT_CATEGORY, placeDraftCategory);
        saved.set(PLACE_QUERY, submittedPlaceQuery); saved.set(PLACE_REGION, submittedPlaceRegion); saved.set(PLACE_CATEGORY, submittedPlaceCategory);
    }
    private void clearPlaceSavedState() {
        placeDraftQuery = ""; placeDraftRegion = ""; placeDraftCategory = ""; submittedPlaceQuery = null; submittedPlaceRegion = null; submittedPlaceCategory = null; selectedPlaceId = null; placeSaveRequestId = null; placeSaveIntentKey = null;
        saved.set(PLACE_DRAFT_QUERY, null); saved.set(PLACE_DRAFT_REGION, null); saved.set(PLACE_DRAFT_CATEGORY, null); saved.set(PLACE_QUERY, null); saved.set(PLACE_REGION, null); saved.set(PLACE_CATEGORY, null); saved.set(PLACE_SELECTED_ID, null); saved.set(PLACE_ANCHOR_ID, null); saved.set(PLACE_ANCHOR_OFFSET, null); saved.set(PLACE_PAGE_DEPTH, null);
    }
    private void saveEdit() {
        saved.set(EDIT_NAME, editDraft.name()); saved.set(EDIT_REGION, editDraft.regionLabel()); saved.set(EDIT_START, editDraft.startDate()); saved.set(EDIT_END, editDraft.endDate());
        saved.set(EDIT_REQUEST_ID, editRequestId); saved.set(EDIT_KEY, editIntentKey);
    }
    private void saveFlow() {
        TripFlowState.Saved state = flow.save();
        saved.set(DRAFT_NAME, state.draft().name()); saved.set(DRAFT_REGION, state.draft().regionLabel());
        saved.set(DRAFT_START, state.draft().startDate()); saved.set(DRAFT_END, state.draft().endDate());
        TripFlowState.Pending pending = state.pending();
        saved.set(PENDING_ID, pending == null ? null : pending.requestId());
        saved.set(PENDING_NAME, pending == null ? null : pending.normalizedDraft().name());
        saved.set(PENDING_REGION, pending == null ? null : pending.normalizedDraft().regionLabel());
        saved.set(PENDING_START, pending == null ? null : pending.normalizedDraft().startDate());
        saved.set(PENDING_END, pending == null ? null : pending.normalizedDraft().endDate());
    }
    private String string(String key) { String value = saved.get(key); return value == null ? "" : value; }

    private static EditDraft normalize(EditDraft draft) { return new EditDraft(normalizeText(draft.name()), normalizeText(draft.regionLabel()), draft.startDate().trim(), draft.endDate().trim()); }
    private static String normalizeText(String value) { return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFC); }
    private static String value(String nullable) { return nullable == null ? "" : nullable; }
    private static String normalizeSearch(String raw) {
        String value = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(value.length()); boolean whitespace = false;
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset); offset += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) whitespace = true;
            else { if (whitespace && result.length() > 0) result.append(' '); result.appendCodePoint(point); whitespace = false; }
        }
        return result.toString();
    }
    private static boolean hasIsoControl(String value) { for (int offset = 0; offset < value.length();) { int point = value.codePointAt(offset); if (Character.isISOControl(point)) return true; offset += Character.charCount(point); } return false; }
    private static String optionalSearch(String value) { String normalized = normalizeSearch(value); return normalized.isEmpty() ? null : normalized; }

    static boolean valid(TripFlowState.Draft draft) {
        if (draft.name().isBlank() || draft.regionLabel().isBlank() || draft.name().codePointCount(0, draft.name().length()) > 100 || draft.regionLabel().codePointCount(0, draft.regionLabel().length()) > 100) return false;
        java.util.Date start = parseDate(draft.startDate()); java.util.Date end = parseDate(draft.endDate());
        return start != null && end != null && !start.after(end);
    }
    private static java.util.Date parseDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); parser.setLenient(false); parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0); java.util.Date parsed = parser.parse(value, position);
        return parsed == null || position.getIndex() != value.length() || !parser.format(parsed).equals(value) ? null : parsed;
    }
    public static final class Factory extends AbstractSavedStateViewModelFactory {
        private final AuthApiClient api;
        public Factory(SavedStateRegistryOwner owner, AuthApiClient api) { super(owner, null); this.api = api; }
        @NonNull @Override protected <T extends ViewModel> T create(@NonNull String key, @NonNull Class<T> modelClass, @NonNull SavedStateHandle handle) {
            if (!modelClass.isAssignableFrom(TripViewModel.class)) throw new IllegalArgumentException("Unknown ViewModel");
            return modelClass.cast(new TripViewModel(api, handle));
        }
    }
}
