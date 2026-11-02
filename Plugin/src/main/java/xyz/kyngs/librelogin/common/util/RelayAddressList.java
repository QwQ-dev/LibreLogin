/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The network's relay addresses: every player behind one of these shares it, so it says nothing about who they are.
 *
 * <p>The one place to edit is a host file, bind-mounted read-only into both the backend and the Velocity container at
 * {@link #SOURCE}. One address or CIDR per line, IPv4 or IPv6, {@code #} starts a comment. A change is picked up within
 * a few seconds, with no restart. This class exists twice, here and in the LibreLogin fork, because the proxy and the
 * backend share no code; the two copies differ only in their package.
 *
 * <h2>When the file cannot be trusted</h2>
 *
 * <p>The answer never gets wider by accident. A missing or unreadable file, an empty one (a write caught half way) or
 * one with any line that does not parse keeps the last good list, also across a restart through {@code lastGood}. A
 * range wider than /16 or /32 is refused, so a slip cannot make everybody relayed. With no list at all nobody is
 * relayed, which is how every address rule behaved before this existed. To clear the list on purpose, leave a comment
 * in the file.
 *
 * <p>Literals only. Nothing here can reach DNS, and {@link #contains(InetAddress)} reads memory: the file is looked at
 * on {@code refresher}, at most once per {@link #RECHECK_MILLIS}.
 */
public final class RelayAddressList {

    /** Where the mount puts the file, in both containers. */
    public static final Path SOURCE = Path.of("/pillage-global/relay-addresses.txt");

    static final long RECHECK_MILLIS = 5_000L;
    static final int WIDEST_IPV4_PREFIX = 16;
    static final int WIDEST_IPV6_PREFIX = 32;
    private static final long LARGEST_FILE_BYTES = 64 * 1024L;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private final Path source;
    private final Path lastGood;
    private final LongSupplier clock;
    private final Executor refresher;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final AtomicLong nextCheck = new AtomicLong();

    private volatile List<Range> ranges = List.of();
    private String stamp;
    private String reported;

    /**
     * @param source    the shared file
     * @param lastGood  a private copy of the last list that parsed; null for none
     * @param clock     milliseconds
     * @param refresher runs a refresh that came due; the caller of {@link #contains(InetAddress)} is not kept waiting
     *                  unless this runs it in place
     */
    public RelayAddressList(Path source, Path lastGood, LongSupplier clock, Executor refresher, Consumer<String> info,
            Consumer<String> warning) {
        this.source = Objects.requireNonNull(source, "source");
        this.lastGood = lastGood;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.info = Objects.requireNonNull(info, "info");
        this.warning = Objects.requireNonNull(warning, "warning");
        restoreLastGood();
        refresh();
        nextCheck.set(clock.getAsLong() + RECHECK_MILLIS);
    }

    /** Whether this address is a listed relay; false for null. */
    public boolean contains(InetAddress address) {
        recheckIfDue();
        if (address == null) {
            return false;
        }
        byte[] bytes = canonical(address.getAddress());
        for (Range range : ranges) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** The same for an address as text; anything that is not an address literal is not a relay. */
    public boolean contains(String literal) {
        InetAddress parsed = literal == null ? null : parseLiteral(literal.trim());
        return contains(parsed);
    }

    /** The current list as its entries, for the log. */
    public List<String> entries() {
        return ranges.stream().map(Range::text).toList();
    }

    private void recheckIfDue() {
        long now = clock.getAsLong();
        long due = nextCheck.get();
        if (now < due || !nextCheck.compareAndSet(due, now + RECHECK_MILLIS)) {
            return;
        }
        try {
            refresher.execute(this::refresh);
        } catch (RuntimeException shuttingDown) {
            // the list stays as it is; the next question tries again
        }
    }

    /** Reads the file if it changed. Never throws, and never leaves the list worse than it was. */
    public synchronized void refresh() {
        String content;
        try {
            long size = Files.size(source);
            String current = Files.getLastModifiedTime(source).toMillis() + ":" + size;
            if (current.equals(stamp)) {
                return;
            }
            if (size > LARGEST_FILE_BYTES) {
                keep("it is larger than " + LARGEST_FILE_BYTES + " bytes");
                return;
            }
            content = Files.readString(source, StandardCharsets.UTF_8);
            stamp = current;
        } catch (IOException | RuntimeException unreadable) {
            stamp = null;
            keep("it cannot be read (" + unreadable.getClass().getSimpleName() + ")");
            return;
        }
        if (content.isEmpty()) {
            keep("it is empty; to clear the list on purpose, leave a comment in it");
            return;
        }
        List<Range> parsed;
        try {
            parsed = parse(content);
        } catch (IllegalArgumentException bad) {
            keep(bad.getMessage());
            return;
        }
        reported = null;
        adopt(parsed);
        saveLastGood(parsed);
    }

    private void adopt(List<Range> parsed) {
        if (!parsed.equals(ranges)) {
            ranges = List.copyOf(parsed);
            info.accept("Relay addresses in force: " + entries() + " (from " + source + ")");
        }
    }

    private void keep(String why) {
        String line = "Relay address file " + source + " was not used: " + why + ". Keeping " + entries();
        if (!line.equals(reported)) {
            reported = line;
            warning.accept(line);
        }
    }

    private void restoreLastGood() {
        if (lastGood == null || !Files.isRegularFile(lastGood)) {
            return;
        }
        try {
            ranges = List.copyOf(parse(Files.readString(lastGood, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException unusable) {
            warning.accept("The saved relay list " + lastGood + " was not used: " + unusable.getMessage());
        }
    }

    private void saveLastGood(List<Range> parsed) {
        if (lastGood == null) {
            return;
        }
        try {
            Path temporary = lastGood.resolveSibling(lastGood.getFileName() + ".tmp");
            StringBuilder text = new StringBuilder("# a copy of the last good relay list; edit the shared file, not this\n");
            parsed.forEach(range -> text.append(range.text()).append('\n'));
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, lastGood, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException notSaved) {
            warning.accept("The relay list could not be saved to " + lastGood + ": " + notSaved.getMessage());
        }
    }

    static List<Range> parse(String content) {
        List<Range> parsed = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            int comment = raw.indexOf('#');
            String line = (comment < 0 ? raw : raw.substring(0, comment)).trim();
            if (!line.isEmpty()) {
                for (String token : line.split("[\\s,]+")) {
                    parsed.add(Range.of(token));
                }
            }
        }
        return parsed;
    }

    /** An address literal, or null; never a lookup. */
    static InetAddress parseLiteral(String text) {
        try {
            if (IPV4.matcher(text).matches()) {
                String[] parts = text.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int part = Integer.parseInt(parts[i]);
                    if (part > 255) {
                        return null;
                    }
                    bytes[i] = (byte) part;
                }
                return InetAddress.getByAddress(bytes);
            }
            // with a colon in it getByName only ever parses: it refuses rather than asks a resolver
            return IPV6.matcher(text).matches() ? InetAddress.getByName(text) : null;
        } catch (UnknownHostException notALiteral) {
            return null;
        }
    }

    /** An IPv4-mapped IPv6 address is the IPv4 address it carries. */
    static byte[] canonical(byte[] bytes) {
        if (bytes.length == 16) {
            boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
            for (int i = 0; mapped && i < 10; i++) {
                mapped = bytes[i] == 0;
            }
            if (mapped) {
                return Arrays.copyOfRange(bytes, 12, 16);
            }
        }
        return bytes;
    }

    /** One line of the list: an address and how many leading bits of it count. */
    record Range(String text, byte[] network, int prefix) {

        static Range of(String token) {
            int slash = token.indexOf('/');
            InetAddress address = parseLiteral(slash < 0 ? token : token.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("'" + token + "' is not an address");
            }
            byte[] network = canonical(address.getAddress());
            int bits = network.length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(token.substring(slash + 1));
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException("'" + token + "' has no usable prefix");
                }
            }
            int widest = bits == 32 ? WIDEST_IPV4_PREFIX : WIDEST_IPV6_PREFIX;
            if (prefix < widest || prefix > bits) {
                throw new IllegalArgumentException("'" + token + "' must have a prefix from /" + widest + " to /" + bits);
            }
            return new Range(token, network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int left = prefix % 8;
            if (left == 0) {
                return true;
            }
            int mask = 0xFF << (8 - left);
            return (address[whole] & mask) == (network[whole] & mask);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Range range && prefix == range.prefix && Arrays.equals(network, range.network);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(network) + prefix;
        }
    }
}
