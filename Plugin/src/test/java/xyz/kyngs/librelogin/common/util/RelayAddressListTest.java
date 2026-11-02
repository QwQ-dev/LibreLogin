/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayAddressListTest {

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String> warnings = new ArrayList<>();

    private Path source() {
        return folder.resolve("relay-addresses.txt");
    }

    private Path saved() {
        return folder.resolve("saved");
    }

    private RelayAddressList list() {
        return new RelayAddressList(source(), saved(), now::get, Runnable::run, line -> {
        }, warnings::add);
    }

    private int edits;

    /** Writes the file and moves its time on, so a same-size rewrite still counts as a change. */
    private void write(String content) throws Exception {
        Files.writeString(source(), content);
        Files.setLastModifiedTime(source(), FileTime.fromMillis(2_000_000L + ++edits * 1000L));
    }

    private void comeDue(RelayAddressList list) throws Exception {
        now.addAndGet(RelayAddressList.RECHECK_MILLIS);
        list.contains(InetAddress.getByName("192.0.2.200"));
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void matchesExactAddressesCidrAndIpv6() throws Exception {
        write("# the relay\n64.90.26.142\n10.20.30.0/24 # a block\n2001:db8:1::/48\n2001:db8:2::7\n");
        RelayAddressList list = list();
        assertTrue(list.contains(ip("64.90.26.142")));
        assertFalse(list.contains(ip("64.90.26.143")));
        assertTrue(list.contains(ip("10.20.30.255")));
        assertFalse(list.contains(ip("10.20.31.0")));
        assertTrue(list.contains(ip("2001:db8:1:ffff::1")));
        assertFalse(list.contains(ip("2001:db8:3::1")));
        assertTrue(list.contains(ip("2001:db8:2::7")));
        assertFalse(list.contains(ip("2001:db8:2::8")));
        assertFalse(list.contains((InetAddress) null));
    }

    @Test
    void aMappedAddressIsTheIpv4ItCarries() throws Exception {
        write("64.90.26.142\n");
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(ip("64.90.26.142").getAddress(), 0, mapped, 12, 4);
        assertTrue(list().contains(Inet6Address.getByAddress(null, mapped, -1)));
    }

    @Test
    void textIsOnlyEverALiteral() throws Exception {
        write("64.90.26.142\n");
        RelayAddressList list = list();
        assertTrue(list.contains("64.90.26.142"));
        assertFalse(list.contains("localhost"));
        assertFalse(list.contains("64.90.26"));
        assertFalse(list.contains("300.90.26.142"));
        assertFalse(list.contains((String) null));
    }

    @Test
    void aChangeArrivesWithoutARestart() throws Exception {
        write("64.90.26.142\n");
        RelayAddressList list = list();
        write("198.51.100.9\n");
        assertTrue(list.contains(ip("64.90.26.142")), "not due yet");
        comeDue(list);
        assertFalse(list.contains(ip("64.90.26.142")));
        assertTrue(list.contains(ip("198.51.100.9")));
    }

    @Test
    void aWideOrBadLineKeepsTheWholeLastGoodList() throws Exception {
        write("64.90.26.142\n");
        RelayAddressList list = list();
        for (String bad : List.of("0.0.0.0/0\n", "64.90.0.0/15\n", "::/0\n", "2001:db8::/31\n", "relay.example.com\n",
                "198.51.100.9\nnonsense\n", "64.90.26.142/33\n", "64.90.26.142/x\n")) {
            write(bad);
            comeDue(list);
            assertTrue(list.contains(ip("64.90.26.142")), bad);
            assertFalse(list.contains(ip("8.8.8.8")), bad);
            assertFalse(list.contains(ip("198.51.100.9")), bad);
        }
        assertFalse(warnings.isEmpty());
    }

    @Test
    void aMissingOrEmptyFileKeepsTheList() throws Exception {
        write("64.90.26.142\n");
        RelayAddressList list = list();
        write("");
        comeDue(list);
        assertTrue(list.contains(ip("64.90.26.142")), "empty: a write caught half way");
        Files.delete(source());
        comeDue(list);
        assertTrue(list.contains(ip("64.90.26.142")), "gone");
    }

    @Test
    void aCommentClearsTheListOnPurpose() throws Exception {
        write("64.90.26.142\n");
        RelayAddressList list = list();
        write("# no relay for now\n");
        comeDue(list);
        assertFalse(list.contains(ip("64.90.26.142")));
    }

    @Test
    void theLastGoodListSurvivesARestartWithoutTheFile() throws Exception {
        write("64.90.26.142\n");
        list();
        Files.delete(source());
        assertTrue(list().contains(ip("64.90.26.142")));
    }

    @Test
    void withNoListAtAllNobodyIsRelayed() throws Exception {
        assertFalse(list().contains(ip("64.90.26.142")));
        assertEquals(List.of(), list().entries());
    }

    @Test
    void aRepeatedTroubleIsSaidOnce() throws Exception {
        write("nonsense\n");
        RelayAddressList list = list();
        int after = warnings.size();
        comeDue(list);
        comeDue(list);
        assertEquals(1, after);
        assertEquals(1, warnings.size());
    }

    @Test
    void parseRefusesAHostName() {
        assertThrows(IllegalArgumentException.class, () -> RelayAddressList.parse("example.com"));
    }
}
