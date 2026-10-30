/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.premium;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.api.util.ThrowableFunction;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pillage: a fallback API's "no such name" is not trusted; only Mojang may say a name is free.
 */
class AuthenticPremiumProviderTest {

    private static final PremiumUser STEVE = new PremiumUser(UUID.randomUUID(), "Steve", true);

    private AuthenticLibreLogin<?, ?> plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(AuthenticLibreLogin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
    }

    private static ThrowableFunction<String, PremiumUser, PremiumException> failing() {
        return name -> {
            throw new PremiumException(PremiumException.Issue.SERVER_EXCEPTION, "down");
        };
    }

    private static ThrowableFunction<String, PremiumUser, PremiumException> answering(PremiumUser user) {
        return name -> user;
    }

    @Test
    void mojangNotFoundIsTrusted() throws PremiumException {
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(
                answering(null),
                name -> fail("a fallback must not be asked when Mojang answered")
        ));

        assertNull(provider.getUserForName("Steve"));
    }

    @Test
    void fallbackNotFoundIsAFailure() {
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(failing(), answering(null), answering(null)));

        var e = assertThrows(PremiumException.class, () -> provider.getUserForName("Steve"));
        assertEquals(PremiumException.Issue.SERVER_EXCEPTION, e.getIssue());
    }

    @Test
    void fallbackNotFoundTriesTheNextFallback() throws PremiumException {
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(failing(), answering(null), answering(STEVE)));

        assertEquals(STEVE, provider.getUserForName("Steve"));
    }

    @Test
    void positiveFallbackAnswerIsUsed() throws PremiumException {
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(failing(), answering(STEVE), answering(null)));

        assertEquals(STEVE, provider.getUserForName("Steve"));
    }

    @Test
    void lastFallbackFailingStillThrows() {
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(failing(), answering(null), failing()));

        assertThrows(PremiumException.class, () -> provider.getUserForName("Steve"));
    }

    @Test
    void failureIsNeverCachedAsAbsent() throws PremiumException {
        var mojang = new AtomicReference<ThrowableFunction<String, PremiumUser, PremiumException>>(failing());
        var calls = new AtomicInteger();
        var provider = TestPremiumProviders.withFetchers(plugin, List.of(
                name -> {
                    calls.incrementAndGet();
                    return mojang.get().apply(name);
                },
                answering(null)
        ));

        assertThrows(PremiumException.class, () -> provider.getUserForName("Steve"));

        mojang.set(answering(STEVE));
        assertEquals(STEVE, provider.getUserForName("Steve"));
        assertEquals(2, calls.get(), "the failed lookup must not have been cached");

        // A positive answer is cached as before
        assertEquals(STEVE, provider.getUserForName("steve"));
        assertEquals(2, calls.get());
    }
}
