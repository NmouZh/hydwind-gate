package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import me.hydwind.gate.auth.LoginSession.Status;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LoginManagerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String GAME_ID = "PlayerOne";
    private static final String IP = "192.0.2.1";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration GRACE = Duration.ofMinutes(5);

    private LoginSessionStore store;
    private FakeWebsiteClient website;
    private FakePlayerGateway gateway;
    private MutableClock clock;
    private Messages messages;
    private LoginManager manager;

    @BeforeEach
    void setUp() {
        store = new LoginSessionStore(GRACE);
        website = new FakeWebsiteClient();
        gateway = new FakePlayerGateway();
        clock = new MutableClock(START);
        messages = Messages.defaults();
        manager = new LoginManager(website, store, gateway, messages,
                LOGIN_TIMEOUT, clock, Runnable::run);
    }

    @Test
    void joinFreezesPlayerAndShowsDialog() {
        LoginSession session = manager.handleJoin(PLAYER, GAME_ID, IP);

        assertNotNull(session);
        assertEquals(Status.PENDING, session.status());
        assertEquals(Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(PLAYER), gateway.frozen);
        assertEquals(List.of(PLAYER), gateway.dialogsShown);
        assertTrue(gateway.messages.isEmpty());
    }

    @Test
    void reconnectWithinGraceIsAllowedWithoutFreeze() {
        store.join(PLAYER, GAME_ID, IP, START);
        store.beginVerification(PLAYER, START);
        store.authenticationSucceeded(PLAYER, START.plusSeconds(1));
        store.quit(PLAYER, START.plusSeconds(2));
        clock.set(START.plus(Duration.ofMinutes(1)));

        LoginSession session = manager.handleJoin(PLAYER, GAME_ID, IP);

        assertTrue(session.isAuthenticated());
        assertTrue(gateway.frozen.isEmpty());
        assertTrue(gateway.dialogsShown.isEmpty());
    }

    @Test
    void submitValidCodeUnfreezesAndGreetsNickname() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        website.next = VerifyResult.ok(GAME_ID, "小风", "player");

        manager.submitCode(PLAYER, "  (｡･ω･｡)  ");

        assertEquals(Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
        assertEquals("(｡･ω･｡)", website.lastCode);
        assertEquals(GAME_ID, website.lastGameId);
        assertEquals(PLAYER, website.lastUuid);
        assertEquals(List.of(PLAYER), gateway.unfrozen);
        assertEquals(1, gateway.messages.size());
        assertTrue(gateway.messages.get(0).contains("小风"),
                () -> "message should contain nickname: " + gateway.messages.get(0));
    }

    @Test
    void submitValidCodeFallsBackToUsernameWhenNicknameIsBlank() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        website.next = VerifyResult.ok(GAME_ID, "  ", "player");

        manager.submitCode(PLAYER, "code");

        assertEquals(1, gateway.messages.size());
        assertTrue(gateway.messages.get(0).contains(GAME_ID),
                () -> "message should contain username: " + gateway.messages.get(0));
        assertEquals(List.of(PLAYER), gateway.unfrozen);
    }

    static Stream<Arguments> rejectedCodes() {
        Messages messages = Messages.defaults();
        return Stream.of(
                Arguments.of(VerifyResult.invalidCode(), messages.invalidCode()),
                Arguments.of(VerifyResult.expired(), messages.expiredCode()),
                Arguments.of(VerifyResult.used(), messages.usedCode()),
                Arguments.of(VerifyResult.gameIdMismatch(), messages.gameIdMismatch())
        );
    }

    @ParameterizedTest
    @MethodSource("rejectedCodes")
    void rejectedCodeReturnsToPendingAndReopensDialog(VerifyResult result, String expectedMessage) {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        gateway.dialogsShown.clear();
        website.next = result;

        manager.submitCode(PLAYER, "bad-code");

        assertEquals(Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(expectedMessage), gateway.messages);
        assertEquals(List.of(PLAYER), gateway.dialogsShown);
        assertTrue(gateway.unfrozen.isEmpty());
    }

    @Test
    void unreachableWebsiteKeepsSessionPendingFailClosed() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        gateway.dialogsShown.clear();
        website.next = VerifyResult.unreachable();

        manager.submitCode(PLAYER, "code");

        assertEquals(Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(messages.serviceUnavailable()), gateway.messages);
        assertEquals(List.of(PLAYER), gateway.dialogsShown);
        assertTrue(gateway.unfrozen.isEmpty());
    }

    @Test
    void unauthorizedPluginTokenIsTreatedAsServiceFailure() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        gateway.dialogsShown.clear();
        website.next = VerifyResult.unauthorized();

        manager.submitCode(PLAYER, "code");

        assertEquals(Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(messages.serviceMisconfigured()), gateway.messages);
        assertEquals(List.of(PLAYER), gateway.dialogsShown);
        assertTrue(gateway.unfrozen.isEmpty());
    }

    @Test
    void tickKicksSessionThatExceededLoginTimeout() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        clock.set(START.plus(LOGIN_TIMEOUT));

        manager.tick();

        assertEquals(Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(PLAYER), gateway.kicked);
        assertEquals(List.of(messages.sessionTimeout()), gateway.kickReasons);
    }

    @Test
    void tickDoesNotKickAuthenticatedSession() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        website.next = VerifyResult.ok(GAME_ID, "小风", "player");
        manager.submitCode(PLAYER, "code");
        clock.set(START.plus(Duration.ofHours(1)));

        manager.tick();

        assertTrue(gateway.kicked.isEmpty());
        assertEquals(Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
    }

    @Test
    void tickClearsGraceRecordOnlyAfterReconnectWindow() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        website.next = VerifyResult.ok(GAME_ID, "小风", "player");
        manager.submitCode(PLAYER, "code");
        manager.handleQuit(PLAYER);

        clock.set(START.plus(GRACE.minusSeconds(1)));
        manager.tick();
        assertTrue(store.find(PLAYER).isPresent());

        clock.set(START.plus(GRACE.plusSeconds(1)));
        manager.tick();
        assertFalse(store.find(PLAYER).isPresent());
    }

    @Test
    void emptyCodeDoesNotCallWebsite() {
        manager.handleJoin(PLAYER, GAME_ID, IP);

        manager.submitCode(PLAYER, "   ");

        assertEquals(0, website.verifyCalls);
        assertEquals(Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(messages.emptyCode()), gateway.messages);
    }

    @Test
    void reopenDialogShowsDialogAgainForPendingPlayer() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        gateway.dialogsShown.clear();

        manager.reopenDialog(PLAYER);

        assertEquals(List.of(PLAYER), gateway.dialogsShown);
    }

    @Test
    void submitCodeAsyncRunsThroughInjectedExecutor() {
        manager.handleJoin(PLAYER, GAME_ID, IP);
        website.next = VerifyResult.ok(GAME_ID, "小风", "player");

        manager.submitCodeAsync(PLAYER, "code");

        assertEquals(1, website.verifyCalls);
        assertEquals(List.of(PLAYER), gateway.unfrozen);
    }

    private static final class FakeWebsiteClient implements WebsiteClient {

        private VerifyResult next = VerifyResult.unreachable();
        private int verifyCalls;
        private String lastCode;
        private String lastGameId;
        private UUID lastUuid;

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            verifyCalls++;
            lastCode = code;
            lastGameId = gameId;
            lastUuid = uuid;
            return next;
        }

        @Override
        public HealthResult health() {
            return HealthResult.unhealthy();
        }
    }

    private static final class FakePlayerGateway implements PlayerGateway {

        private final List<UUID> frozen = new ArrayList<>();
        private final List<UUID> unfrozen = new ArrayList<>();
        private final List<UUID> dialogsShown = new ArrayList<>();
        private final List<UUID> kicked = new ArrayList<>();
        private final List<String> kickReasons = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(UUID playerId, String message) {
            messages.add(message);
        }

        @Override
        public void kick(UUID playerId, String reason) {
            kicked.add(playerId);
            kickReasons.add(reason);
        }

        @Override
        public void freeze(UUID playerId) {
            frozen.add(playerId);
        }

        @Override
        public void unfreeze(UUID playerId) {
            unfrozen.add(playerId);
        }

        @Override
        public void showLoginDialog(UUID playerId) {
            dialogsShown.add(playerId);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}