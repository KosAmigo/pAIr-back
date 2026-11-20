package com.pair.exam;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;

/** 이 PC의 사내 네트워크(와이파이·유선) IPv4. WSL·Docker·VM 같은 가상 어댑터는 건너뛴다. */
public final class LanAddress {
    private static final Pattern VIRTUAL = Pattern.compile("(?i)vethernet|wsl|docker|vmware|virtualbox|vbox|hyper-v|loopback|tailscale|zerotier|bluetooth");

    private LanAddress() {}

    public static Optional<String> find() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || VIRTUAL.matcher(ni.getDisplayName() + " " + ni.getName()).find()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return Optional.of(a.getHostAddress());
                }
            }
        } catch (java.net.SocketException ignored) {
            // 찾지 못하면 localhost 로 둔다
        }
        return Optional.empty();
    }
}
