/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.velocity;

import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.ReadWriteDatabaseProvider;
import xyz.kyngs.librelogin.common.config.HoconMessages;
import xyz.kyngs.librelogin.common.database.AuthenticUser;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pillage: an online login whose Mojang UUID differs from the stored premium UUID is refused.
 */
class VelocityListenersTest {

    private static final UUID OLD_OWNER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID NEW_OWNER = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("203.0.113.7", 50123);

    private ReadWriteDatabaseProvider db;
    private VelocityListeners listeners;

    @BeforeEach
    void setUp() {
        var plugin = mock(VelocityLibreLogin.class);
        db = mock(ReadWriteDatabaseProvider.class);
        var messages = mock(HoconMessages.class, invocation -> invocation.getMethod().getName().equals("getMessage")
                ? Component.text(Arrays.stream(invocation.getArguments()).map(String::valueOf).collect(Collectors.joining(",")).replaceFirst(",", ":"))
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
        when(plugin.getDatabaseProvider()).thenReturn(db);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        listeners = new VelocityListeners(plugin);
    }

    private static AuthenticUser row(String name, UUID uuid, UUID premiumUUID) {
        var now = new Timestamp(System.currentTimeMillis());
        return new AuthenticUser(uuid, premiumUUID, null, name, now, now, null, "127.0.0.1", null, null, null);
    }

    private static GameProfileRequestEvent profileRequest(InetSocketAddress address, String name, UUID id, boolean online) {
        var connection = mock(InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(address);
        return new GameProfileRequestEvent(connection, new GameProfile(id, name, List.of()), online);
    }

    // LoginEvent's Player cannot be mocked against adventure 4.14.0, so the listener's refusal is checked directly
    private Component refusal(InetSocketAddress address, String name) {
        return listeners.loginRefusal(address, name);
    }

    @Test
    void mismatchedMojangUUIDIsRefused() {
        when(db.getByName("Steve")).thenReturn(row("Steve", OLD_OWNER, OLD_OWNER));

        var request = profileRequest(ADDRESS, "Steve", NEW_OWNER, true);
        listeners.onProfileRequest(request);

        assertEquals(NEW_OWNER, request.getGameProfile().getId(), "the stored UUID must not be handed out");

        assertEquals(Component.text("kick-name-mismatch:%nickname%,Steve"), refusal(ADDRESS, "Steve"));

        // The flag is consumed
        assertNull(refusal(ADDRESS, "Steve"));
    }

    @Test
    void matchingMojangUUIDGetsTheStoredProfile() {
        var stored = UUID.randomUUID(); // e.g. new-uuid-creator=RANDOM, uuid differs from the premium one
        when(db.getByName("Steve")).thenReturn(row("Steve", stored, OLD_OWNER));

        var request = profileRequest(ADDRESS, "Steve", OLD_OWNER, true);
        listeners.onProfileRequest(request);
        assertEquals(stored, request.getGameProfile().getId());

        assertNull(refusal(ADDRESS, "Steve"));
    }

    @Test
    void offlineModeIsUnchanged() {
        var cracked = GeneralUtil.getCrackedUUIDFromName("Steve");
        when(db.getByName("Steve")).thenReturn(row("Steve", cracked, null));

        var request = profileRequest(ADDRESS, "Steve", cracked, false);
        listeners.onProfileRequest(request);
        assertEquals(cracked, request.getGameProfile().getId());

        assertNull(refusal(ADDRESS, "Steve"));
    }

    @Test
    void refusalIsScopedToTheConnection() {
        when(db.getByName("Steve")).thenReturn(row("Steve", OLD_OWNER, OLD_OWNER));

        listeners.onProfileRequest(profileRequest(ADDRESS, "Steve", NEW_OWNER, true));

        assertNull(refusal(new InetSocketAddress("198.51.100.9", 40000), "Steve"));
        assertNotNull(refusal(ADDRESS, "Steve"));
    }
}
