package org.riptide.repository.postgres;

import java.net.InetAddress;

public final class PostgresUtils {
    private PostgresUtils() {

    }

    public static String cleanIp(InetAddress inetAddress) {
        if (inetAddress != null) {
            return cleanIp(inetAddress.toString());
        }
        return null;
    }

    public static String cleanIp(String string) {
        if (string != null && string.startsWith("/")) {
            return string.substring(1);
        }
        return string;
    }
}
