/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.listener;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.ReadWriteDatabaseProvider;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.config.HoconMessages;
import xyz.kyngs.librelogin.common.config.HoconPluginConfiguration;
import xyz.kyngs.librelogin.common.config.key.ConfigurationKey;
import xyz.kyngs.librelogin.common.database.AuthenticUser;
import xyz.kyngs.librelogin.common.premium.AuthenticPremiumProvider;
import xyz.kyngs.librelogin.common.premium.TestPremiumProviders;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pillage: registration never hands a premium UUID to an offline row, unreliable data is refused, and a Mojang
 * outage is decided from our own rows.
 */
class AuthenticListenersTest {

    private static final InetAddress IP = InetAddress.getLoopbackAddress();
    private static final UUID PREMIUM = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private AuthenticLibreLogin<Object, Object> plugin;
    private ReadWriteDatabaseProvider db;
    private AuthenticPremiumProvider premium;
    private Logger logger;
    private final Map<ConfigurationKey<?>, Object> config = new HashMap<>();
    private AuthenticListeners<AuthenticLibreLogin<Object, Object>, Object, Object> listeners;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        plugin = mock(AuthenticLibreLogin.class);
        db = mock(ReadWriteDatabaseProvider.class);
        premium = mock(AuthenticPremiumProvider.class);
        logger = mock(Logger.class);

        var messages = mock(HoconMessages.class, invocation -> invocation.getMethod().getName().equals("getMessage")
                ? Component.text((String) invocation.getArgument(0))
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
        var configuration = mock(HoconPluginConfiguration.class, invocation -> invocation.getMethod().getName().equals("get")
                ? config.get(invocation.<ConfigurationKey<?>>getArgument(0))
                : Mockito.RETURNS_DEFAULTS.answer(invocation));

        config.put(ConfigurationKeys.AUTO_REGISTER, true);
        config.put(ConfigurationKeys.MINIMUM_USERNAME_LENGTH, 3);
        config.put(ConfigurationKeys.IP_LIMIT, 0);
        config.put(ConfigurationKeys.PROFILE_CONFLICT_RESOLUTION_STRATEGY, "BLOCK");

        when(plugin.getDatabaseProvider()).thenReturn(db);
        when(plugin.getPremiumProvider()).thenReturn(premium);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getConfiguration()).thenReturn(configuration);
        // new-uuid-creator=MOJANG
        when(plugin.generateNewUUID(anyString(), any())).thenAnswer(invocation -> {
            UUID premiumId = invocation.getArgument(1);
            return premiumId == null ? GeneralUtil.getCrackedUUIDFromName(invocation.getArgument(0)) : premiumId;
        });

        listeners = new AuthenticListeners<>(plugin);
    }

    private static AuthenticUser row(String name, UUID premiumUUID) {
        var now = new Timestamp(System.currentTimeMillis());
        var uuid = premiumUUID == null ? GeneralUtil.getCrackedUUIDFromName(name) : premiumUUID;
        return new AuthenticUser(uuid, premiumUUID, null, name, now, now, null, "127.0.0.1", null, null, null);
    }

    private static Component message(String key) {
        return Component.text(key);
    }

    // --- Change 2: never hand a premium UUID to an offline/password row ---

    @Test
    void unreliablePremiumDataIsRefusedAndNothingIsInserted() throws PremiumException {
        when(premium.getUserForName("Steve")).thenReturn(new PremiumUser(PREMIUM, "SomeoneElse", false));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        assertEquals(message("kick-premium-error-undefined"), result.message());
        verify(db, never()).insertUser(any());
    }

    @Test
    void offlineRowNeverGetsThePremiumUUID() throws PremiumException {
        config.put(ConfigurationKeys.AUTO_REGISTER, false);
        when(premium.getUserForName("Steve")).thenReturn(new PremiumUser(PREMIUM, "Steve", true));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.FORCE_OFFLINE, result.state());
        var inserted = ArgumentCaptor.forClass(User.class);
        verify(db).insertUser(inserted.capture());
        assertEquals(GeneralUtil.getCrackedUUIDFromName("Steve"), inserted.getValue().getUuid());
        assertNull(inserted.getValue().getPremiumUUID());
        // The occupied-UUID check looks at the UUID that is inserted, not the premium one
        verify(db).getByUUID(GeneralUtil.getCrackedUUIDFromName("Steve"));
        verify(db, never()).getByUUID(PREMIUM);
    }

    @Test
    void occupiedCheckUsesTheInsertedUUID() throws PremiumException {
        config.put(ConfigurationKeys.AUTO_REGISTER, false);
        when(premium.getUserForName("Steve")).thenReturn(new PremiumUser(PREMIUM, "Steve", true));
        when(db.getByUUID(GeneralUtil.getCrackedUUIDFromName("Steve"))).thenReturn(row("Other", null));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        assertEquals(message("kick-occupied-username"), result.message());
        verify(db, never()).insertUser(any());
    }

    @Test
    void reliablePremiumAutoRegisterStillGetsThePremiumUUID() throws PremiumException {
        when(premium.getUserForName("Steve")).thenReturn(new PremiumUser(PREMIUM, "Steve", true));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.FORCE_ONLINE, result.state());
        var inserted = ArgumentCaptor.forClass(User.class);
        verify(db).insertUser(inserted.capture());
        assertEquals(PREMIUM, inserted.getValue().getUuid());
        assertEquals(PREMIUM, inserted.getValue().getPremiumUUID());
    }

    // --- Change 3: Mojang outage decided from our own rows ---

    private void mojangDown(PremiumException.Issue issue) throws PremiumException {
        when(premium.getUserForName(anyString())).thenThrow(new PremiumException(issue, "down"));
    }

    @Test
    void outageWithKnownPremiumRowForcesOnline() throws PremiumException {
        mojangDown(PremiumException.Issue.SERVER_EXCEPTION);
        var known = row("Steve", PREMIUM);
        when(db.getByName("Steve")).thenReturn(known);

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.FORCE_ONLINE, result.state());
        assertSame(known, result.user());
        verify(logger, times(1)).warn(anyString());
        verify(db, never()).insertUser(any());
    }

    @Test
    void outageWithKnownOfflineRowForcesOffline() throws PremiumException {
        mojangDown(PremiumException.Issue.THROTTLED);
        when(db.getByName("Steve")).thenReturn(row("Steve", null));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.FORCE_OFFLINE, result.state());
        verify(logger, times(1)).warn(anyString());
        verify(db, never()).insertUser(any());
    }

    @Test
    void outageWithUnknownNameIsDeniedAsBefore() throws PremiumException {
        mojangDown(PremiumException.Issue.UNDEFINED);

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        assertEquals(message("kick-premium-error-undefined"), result.message());
        verify(db, never()).insertUser(any());
    }

    @Test
    void outageThrottledUnknownNameKeepsTheThrottledMessage() throws PremiumException {
        mojangDown(PremiumException.Issue.THROTTLED);

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        assertEquals(message("kick-premium-error-throttled"), result.message());
    }

    @Test
    void outageWithDifferentCapitalsIsDenied() throws PremiumException {
        mojangDown(PremiumException.Issue.SERVER_EXCEPTION);
        when(db.getByName("steve")).thenReturn(row("Steve", PREMIUM));

        var result = listeners.onPreLogin("steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        verify(db, never()).insertUser(any());
    }

    // --- Changes 1 + 3 through the real provider: Mojang fails, a fallback says "not found" ---

    @Test
    void untrustedFallbackNotFoundWithUnknownNameIsDenied() {
        var real = TestPremiumProviders.withFetchers(plugin, List.of(
                name -> {
                    throw new PremiumException(PremiumException.Issue.SERVER_EXCEPTION, "mojang down");
                },
                name -> null,
                name -> null
        ));
        when(plugin.getPremiumProvider()).thenReturn(real);

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.DENIED, result.state());
        verify(db, never()).insertUser(any());
    }

    @Test
    void untrustedFallbackNotFoundWithKnownPremiumRowForcesOnline() {
        var real = TestPremiumProviders.withFetchers(plugin, List.of(
                name -> {
                    throw new PremiumException(PremiumException.Issue.SERVER_EXCEPTION, "mojang down");
                },
                name -> null,
                name -> null
        ));
        when(plugin.getPremiumProvider()).thenReturn(real);
        when(db.getByName("Steve")).thenReturn(row("Steve", PREMIUM));

        var result = listeners.onPreLogin("Steve", IP);

        assertEquals(PreLoginState.FORCE_ONLINE, result.state());
        verify(db, never()).insertUser(any());
    }
}
