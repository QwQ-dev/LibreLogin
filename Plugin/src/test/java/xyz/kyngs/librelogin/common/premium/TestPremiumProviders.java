/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.premium;

import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.api.util.ThrowableFunction;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;

import java.util.List;

/**
 * Pillage: test access to the package-private fetcher seam of {@link AuthenticPremiumProvider}.
 */
public final class TestPremiumProviders {

    private TestPremiumProviders() {
    }

    public static AuthenticPremiumProvider withFetchers(AuthenticLibreLogin<?, ?> plugin, List<ThrowableFunction<String, PremiumUser, PremiumException>> fetchers) {
        return new AuthenticPremiumProvider(plugin, fetchers);
    }
}
