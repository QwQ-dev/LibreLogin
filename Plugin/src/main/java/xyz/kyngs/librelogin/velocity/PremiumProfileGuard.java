/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.velocity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.database.User;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Pillage: refuses an online-mode login whose Mojang-authenticated UUID differs from the premium UUID stored
 * for that name (a name that now belongs to another Mojang account must not get the old owner's row).
 * GameProfileRequestEvent cannot deny a login, so the mismatch is remembered per connection (remote address +
 * name) and the login is denied in LoginEvent.
 */
final class PremiumProfileGuard {

    // Value: the stored nickname, for the %nickname% placeholder of kick-name-mismatch.
    private final Cache<String, String> mismatches = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    /**
     * @return true if the stored row's UUID may be assigned to this connection, false if the login must be refused
     */
    boolean allowStoredProfile(InetSocketAddress address, String username, boolean onlineMode, UUID authenticatedId, @Nullable User stored) {
        var key = key(address, username);
        if (onlineMode && stored != null && stored.getPremiumUUID() != null && !stored.getPremiumUUID().equals(authenticatedId)) {
            mismatches.put(key, stored.getLastNickname());
            return false;
        }
        mismatches.invalidate(key);
        return true;
    }

    /**
     * @return the stored nickname if this connection was refused by {@link #allowStoredProfile}, null otherwise
     */
    @Nullable
    String takeMismatch(InetSocketAddress address, String username) {
        var key = key(address, username);
        var nickname = mismatches.getIfPresent(key);
        mismatches.invalidate(key);
        return nickname;
    }

    private static String key(InetSocketAddress address, String username) {
        return address + "|" + username.toLowerCase(Locale.ROOT);
    }
}
