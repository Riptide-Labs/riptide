package org.riptide.repository.postgres;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;

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

    public static Timestamp nullSafeTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return new Timestamp(instant.toEpochMilli());
    }
}
