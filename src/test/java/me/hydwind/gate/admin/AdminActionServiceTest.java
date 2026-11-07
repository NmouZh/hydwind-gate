package me.hydwind.gate.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.login.LimboResidents;
import org.junit.jupiter.api.Test;

/**
 * 穿过 {@link AdminActionService} 的 Interface 测试 approve / kick 分支与顺序不变量。
 *
 * <p>会话用真实的内存 {@link LoginSessionStore}；limbo 与后端玩家用假实现，
 * 假实现会把“调用时会话是否仍在”记进事件，用来验证先写会话再转服/断开的约定。</p>
 */
class AdminActionServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String NAME = "PlayerOne";
    private static final String IP = "192.0.2.1";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration RECONNECT_GRACE = Duration.ofMinutes(5);
    private static final String MESSAGE = "管理员操作";

    private final LoginSessionStore sessions = new LoginSessionStore(RECONNECT_GRACE);
    private final FakeLimboResidents residents = new FakeLimboResidents(sessions);
    private final FakeBackendPlayers backend = new FakeBackendPlayers(sessions);
    private final AdminActionService service = new AdminActionService(
            sessions, residents, backend, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void approveLimboResidentWithoutSessionReturnsNotFound() {
        residents.add(PLAYER, NAME);

        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.NOT_FOUND, result.kind(),
                "没有会话可写时无法通过 ServerPreConnectEvent，不能误报已放行");
        assertEquals(NAME, result.playerName());
        assertEquals(List.of(), residents.events);
    }

    @Test
    void approveRechecksResidentThatRegistersAfterTheFirstLookup() {
        joinSession();
        // 模拟 onSpawn 尚未 register 的竞态：第一次 find 时 limbo 里还没有人，
        // forceAuthenticate 写入后 handler 才完成注册，复查时才能看到玩家。
        LateRegisteringResidents lateResidents =
                new LateRegisteringResidents(sessions, PLAYER, NAME);
        AdminActionService lateService = new AdminActionService(
                sessions, lateResidents, backend, Clock.fixed(NOW, ZoneOffset.UTC));

        AdminResult result = lateService.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.APPROVED_IN_LIMBO, result.kind(),
                "复查时已注册的玩家必须放行，不能误报为 APPROVED_OFFLINE");
        assertEquals(NAME, result.playerName());
        assertTrue(sessions.find(PLAYER).orElseThrow().isAuthenticated());
        assertEquals(2, lateResidents.findCalls,
                "approve 必须在 forceAuthenticate 之后复查一次 resident");
        assertEquals(List.of(approveEvent(PLAYER, true)), lateResidents.events,
                "复查命中后必须实际调用 residents.approve，且此时会话已认证");
    }

    @Test
    void approveOfflineSessionReturnsApprovedOfflineAndAuthenticates() {
        joinSession();

        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.APPROVED_OFFLINE, result.kind());
        assertEquals(NAME, result.playerName());
        assertTrue(sessions.find(PLAYER).orElseThrow().isAuthenticated());
        assertEquals(List.of(), residents.events);
    }

    @Test
    void approveBothWritesSessionBeforeLimboTransfer() {
        joinSession();
        residents.add(PLAYER, NAME);

        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.APPROVED_IN_LIMBO, result.kind());
        assertTrue(sessions.find(PLAYER).orElseThrow().isAuthenticated());
        // authenticated=true 证明 residents.approve 被调用时 forceAuthenticate 已生效。
        assertEquals(List.of(approveEvent(PLAYER, true)), residents.events);
    }

    @Test
    void approveUnknownPlayerReturnsNotFound() {
        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.NOT_FOUND, result.kind());
        assertEquals(NAME, result.playerName());
        assertEquals(List.of(), residents.events);
    }

    @Test
    void approveAlreadyAuthenticatedDoesNotTouchLimbo() {
        joinSession();
        assertTrue(sessions.forceAuthenticate(PLAYER, NOW));
        residents.add(PLAYER, NAME);

        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.ALREADY_AUTHENTICATED, result.kind());
        assertEquals(NAME, result.playerName());
        assertEquals(List.of(), residents.events);
    }

    @Test
    void kickLimboResidentClearsSessionBeforeKick() {
        joinSession();
        residents.add(PLAYER, NAME);
        // 即使后端也存在同名玩家，limbo 分支应优先。
        backend.add(PLAYER, NAME);

        AdminResult result = service.kick(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.KICKED_IN_LIMBO, result.kind());
        assertEquals(NAME, result.playerName());
        assertTrue(sessions.find(PLAYER).isEmpty());
        // sessionPresent=false 证明 sessions.remove 先于 residents.kick。
        assertEquals(List.of(kickEvent(PLAYER, false)), residents.events);
        assertEquals(List.of(), backend.events);
    }

    @Test
    void kickBackendPlayerClearsSessionBeforeDisconnect() {
        joinSession();
        backend.add(PLAYER, NAME);

        AdminResult result = service.kick(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.KICKED_BACKEND, result.kind());
        assertEquals(NAME, result.playerName());
        assertTrue(sessions.find(PLAYER).isEmpty());
        assertEquals(List.of(disconnectEvent(PLAYER, false)), backend.events);
        assertEquals(List.of(), residents.events);
    }

    @Test
    void kickResidualSessionWithoutPlayerOnlyClearsSession() {
        joinSession();

        AdminResult result = service.kick(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.KICKED_BACKEND, result.kind());
        assertEquals(NAME, result.playerName());
        assertTrue(sessions.find(PLAYER).isEmpty());
        assertEquals(List.of(), residents.events);
        assertEquals(List.of(), backend.events);
    }

    @Test
    void kickOnlinePlayerWithoutSessionDisconnectsBackend() {
        backend.add(PLAYER, NAME);

        AdminResult result = service.kick(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.KICKED_BACKEND, result.kind());
        assertEquals(NAME, result.playerName());
        assertEquals(List.of(disconnectEvent(PLAYER, false)), backend.events);
    }

    @Test
    void kickUnknownPlayerReturnsNotFound() {
        AdminResult result = service.kick(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.NOT_FOUND, result.kind());
        assertEquals(NAME, result.playerName());
        assertEquals(List.of(), residents.events);
        assertEquals(List.of(), backend.events);
    }

    @Test
    void approveTimedOutSessionReturnsNotFound() {
        joinSession();
        assertTrue(sessions.timeOut(PLAYER, NOW.plus(LOGIN_TIMEOUT)));

        AdminResult result = service.approve(NAME, MESSAGE);

        assertEquals(AdminResult.Kind.NOT_FOUND, result.kind(),
                "已超时会话正在被断开，放行不成立，不能误报已放行");
        assertEquals(List.of(), residents.events);
    }

    private void joinSession() {
        sessions.join(PLAYER, NAME, IP, NOW, LOGIN_TIMEOUT);
    }

    private static String approveEvent(UUID uuid, boolean authenticated) {
        return "approve:" + uuid + ":authenticated=" + authenticated + ":" + MESSAGE;
    }

    private static String kickEvent(UUID uuid, boolean sessionPresent) {
        return "kick:" + uuid + ":sessionPresent=" + sessionPresent + ":" + MESSAGE;
    }

    private static String disconnectEvent(UUID uuid, boolean sessionPresent) {
        return "disconnect:" + uuid + ":sessionPresent=" + sessionPresent + ":" + MESSAGE;
    }

    /**
     * 模拟 handler 在管理员操作期间才完成 register 的竞态：
     * 第一次 {@link #find(String)} 返回空，之后的查找才返回 resident。
     */
    private static final class LateRegisteringResidents implements LimboResidents {

        private final LoginSessionStore sessions;
        private final Resident resident;
        private final List<String> events = new ArrayList<>();
        private int findCalls;

        LateRegisteringResidents(LoginSessionStore sessions, UUID uuid, String gameId) {
            this.sessions = sessions;
            this.resident = new Resident(uuid, gameId);
        }

        @Override
        public Optional<Resident> find(String gameId) {
            findCalls++;
            if (findCalls == 1) {
                return Optional.empty();
            }
            return resident.gameId().equalsIgnoreCase(gameId)
                    ? Optional.of(resident)
                    : Optional.empty();
        }

        @Override
        public List<Resident> all() {
            return findCalls == 0 ? List.of() : List.of(resident);
        }

        @Override
        public boolean approve(UUID uuid, String message) {
            boolean authenticated = sessions.find(uuid)
                    .map(LoginSession::isAuthenticated)
                    .orElse(false);
            events.add("approve:" + uuid + ":authenticated=" + authenticated + ":" + message);
            return resident.uuid().equals(uuid);
        }

        @Override
        public boolean kick(UUID uuid, String message) {
            return false;
        }
    }

    /** 记录 approve 时会话是否已认证、kick 时会话是否已移除。 */
    private static final class FakeLimboResidents implements LimboResidents {

        private final LoginSessionStore sessions;
        private final Map<UUID, Resident> residents = new HashMap<>();
        private final List<String> events = new ArrayList<>();

        FakeLimboResidents(LoginSessionStore sessions) {
            this.sessions = sessions;
        }

        void add(UUID uuid, String gameId) {
            residents.put(uuid, new Resident(uuid, gameId));
        }

        @Override
        public Optional<Resident> find(String gameId) {
            return residents.values().stream()
                    .filter(resident -> resident.gameId().equalsIgnoreCase(gameId))
                    .findFirst();
        }

        @Override
        public List<Resident> all() {
            return List.copyOf(residents.values());
        }

        @Override
        public boolean approve(UUID uuid, String message) {
            boolean authenticated = sessions.find(uuid)
                    .map(LoginSession::isAuthenticated)
                    .orElse(false);
            events.add("approve:" + uuid + ":authenticated=" + authenticated + ":" + message);
            return residents.containsKey(uuid);
        }

        @Override
        public boolean kick(UUID uuid, String message) {
            boolean sessionPresent = sessions.find(uuid).isPresent();
            events.add("kick:" + uuid + ":sessionPresent=" + sessionPresent + ":" + message);
            return residents.remove(uuid) != null;
        }
    }

    /** 记录 disconnect 时会话是否已移除。 */
    private static final class FakeBackendPlayers implements AdminActionService.BackendPlayers {

        private final LoginSessionStore sessions;
        private final Map<String, UUID> online = new HashMap<>();
        private final Map<UUID, String> names = new HashMap<>();
        private final List<String> events = new ArrayList<>();

        FakeBackendPlayers(LoginSessionStore sessions) {
            this.sessions = sessions;
        }

        void add(UUID uuid, String gameId) {
            online.put(gameId, uuid);
            names.put(uuid, gameId);
        }

        @Override
        public Optional<UUID> findOnline(String gameId) {
            return online.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(gameId))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }

        @Override
        public void disconnect(UUID uuid, String message) {
            events.add("disconnect:" + uuid + ":sessionPresent=" + sessions.find(uuid).isPresent()
                    + ":" + message);
            String gameId = names.get(uuid);
            if (gameId != null) {
                online.remove(gameId);
            }
        }

        @Override
        public Optional<String> nameOf(UUID uuid) {
            return Optional.ofNullable(names.get(uuid));
        }
    }
}