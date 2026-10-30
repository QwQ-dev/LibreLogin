/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import java.util.Objects;

public class VelocityListeners extends AuthenticListeners<VelocityLibreLogin, Player, RegisteredServer> {

    private static final AttributeKey<?> FLOODGATE_ATTR = AttributeKey.valueOf("floodgate-player");
    private static final Field INITIAL_MINECRAFT_CONNECTION;
    private static final Field INITIAL_CONNECTION_DELEGATE;
    private static final Field CHANNEL;

    // Pillage: online UUID must match the stored premium UUID
    private final PremiumProfileGuard premiumProfileGuard = new PremiumProfileGuard();

    static {
        try {
            Class<?> initialConnection = Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection");
            Class<?> minecraftConnection = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection");
            INITIAL_MINECRAFT_CONNECTION = GeneralUtil.getFieldByType(initialConnection, minecraftConnection);
            if (INITIAL_MINECRAFT_CONNECTION != null) {
                INITIAL_MINECRAFT_CONNECTION.setAccessible(true);
            }

            // Since Velocity 3.1.0
            Class<?> loginInboundConnection;
            try {
                loginInboundConnection = Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection");
            } catch (ClassNotFoundException e) {
                loginInboundConnection = null;
            }

            if (loginInboundConnection != null) {
                INITIAL_CONNECTION_DELEGATE = loginInboundConnection.getDeclaredField("delegate");
                INITIAL_CONNECTION_DELEGATE.setAccessible(true);
                Objects.requireNonNull(
                        INITIAL_CONNECTION_DELEGATE,
                        "initial inbound connection delegate cannot be null"
                );
            } else {
                INITIAL_CONNECTION_DELEGATE = null;
            }

            CHANNEL = GeneralUtil.getFieldByType(minecraftConnection, Channel.class);
            if (CHANNEL != null) {
                CHANNEL.setAccessible(true);
            }
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public VelocityListeners(VelocityLibreLogin plugin) {
        super(plugin);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPostLogin(PostLoginEvent event) {
        onPostLogin(event.getPlayer(), null);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        onPlayerDisconnect(event.getPlayer());
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onProfileRequest(GameProfileRequestEvent event) {
        var existing = event.getGameProfile();

        if (existing != null && plugin.fromFloodgate(existing.getId())) return;

        var profile = plugin.getDatabaseProvider().getByName(event.getUsername());

        var gProfile = event.getOriginalProfile();

        // Pillage: in online mode the original profile id is the Mojang-authenticated UUID. If the row found by
        // name belongs to another premium account, do not hand out its UUID; onLogin refuses the connection.
        if (!premiumProfileGuard.allowStoredProfile(event.getConnection().getRemoteAddress(), gProfile.getName(), event.isOnlineMode(), gProfile.getId(), profile)) {
            plugin.getLogger().warn("Refusing %s: Mojang UUID %s does not match the stored premium UUID %s".formatted(gProfile.getName(), gProfile.getId(), profile.getPremiumUUID()));
            return;
        }

        event.setGameProfile(new GameProfile(profile.getUuid(), gProfile.getName(), gProfile.getProperties()));
    }

    // Pillage: deny the logins flagged in onProfileRequest. LAST so no other listener can allow them again.
    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event) {
        var player = event.getPlayer();
        var refusal = loginRefusal(player.getRemoteAddress(), player.getUsername());
        if (refusal != null) event.setResult(ResultedEvent.ComponentResult.denied(refusal));
    }

    // Pillage: package-private for tests (a Velocity Player cannot be mocked against adventure 4.14.0)
    @Nullable
    Component loginRefusal(InetSocketAddress address, String username) {
        var nickname = premiumProfileGuard.takeMismatch(address, username);
        if (nickname == null) return null;

        return plugin.getMessages().getMessage("kick-name-mismatch",
                "%nickname%", nickname
        );
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPreLogin(PreLoginEvent event) {

        if (!event.getResult().isAllowed())
            return;

        // If floodgate is present, attempt to extract the floodgate player from the connection channel.
        if (plugin.floodgateEnabled()) {
            Channel channel;
            InboundConnection connection = event.getConnection();
            try {
                if (INITIAL_CONNECTION_DELEGATE != null) {
                    connection = (InboundConnection) INITIAL_CONNECTION_DELEGATE.get(connection);
                }

                Object mcConnection = INITIAL_MINECRAFT_CONNECTION.get(connection);
                channel = (Channel) CHANNEL.get(mcConnection);

                if (channel.attr(FLOODGATE_ATTR).get() != null) {
                    return; // Player is coming from Floodgate
                }
            } catch (Exception e) {
                plugin.getLogger().warn("Failed to check if player is coming from Floodgate.");
                e.printStackTrace();
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Internal LibreLogin error")));
                return;
            }
        }

        var result = onPreLogin(event.getUsername(), event.getConnection().getRemoteAddress().getAddress());

        event.setResult(
                switch (result.state()) {
                    case DENIED -> {
                        assert result.message() != null;
                        yield PreLoginEvent.PreLoginComponentResult.denied(result.message());
                    }
                    case FORCE_ONLINE -> PreLoginEvent.PreLoginComponentResult.forceOnlineMode();
                    case FORCE_OFFLINE -> PreLoginEvent.PreLoginComponentResult.forceOfflineMode();
                }
        );

    }

    @Subscribe(order = PostOrder.LAST)
    public void chooseServer(PlayerChooseInitialServerEvent event) {
        var server = chooseServer(event.getPlayer(), null, null);

        if (server.value() == null) {
            event.getPlayer().disconnect(plugin.getMessages().getMessage("kick-no-" + (server.key() ? "lobby" : "limbo")));
            event.setInitialServer(null);
        } else {
            event.setInitialServer(server.value());
        }

    }

    @Subscribe(order = PostOrder.EARLY)
    public void onKick(KickedFromServerEvent event) {
        var reason = event.getServerKickReason().orElse(Component.text("null"));
        var message = plugin.getMessages().getMessage("info-kick").replaceText(builder -> builder.matchLiteral("%reason%").replacement(reason));
        var player = event.getPlayer();

        if (event.kickedDuringServerConnect()) {
            event.setResult(KickedFromServerEvent.Notify.create(message));
        } else {
            if (!plugin.getConfiguration().get(ConfigurationKeys.FALLBACK) || plugin.getServerHandler().getLobbyServers().containsValue(event.getServer())) {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
            } else {
                try {
                    var server = plugin.getServerHandler().chooseLobbyServer(plugin.getDatabaseProvider().getByUUID(player.getUniqueId()), player, false, true);

                    if (server == null) throw new NoSuchElementException();

                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(server, message));
                } catch (NoSuchElementException | EventCancelledException e) {
                    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
                }
            }
        }
    }


}
