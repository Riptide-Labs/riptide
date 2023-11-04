package org.riptide.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Objects;

/**
 * Utility class for building the {@link ConversationKey} and
 * converting it to/from a string so that it can be used in
 * group-by statements when querying.
 *
 * @author jwhite
 */
public final class Utils {

    private static final Gson gson = new GsonBuilder().create();

    public static ConversationKey fromJsonString(String json) {
        return gson.fromJson(json, ConversationKey.class);
    }

    public static String getConvoKeyAsJsonString(final String location, final Integer protocol, final String srcAddr, final String dstAddr, final String application) {
        if (location != null && protocol != null && srcAddr != null && dstAddr != null) {
            final var criteria = Objects.compare(srcAddr, dstAddr, String::compareTo) < 0;
            final var lower = criteria ? srcAddr : dstAddr;
            final var higher = criteria ? dstAddr : srcAddr;
            final var conversationKey = new ConversationKey(location, protocol, lower, higher, application);
            return gson.toJson(conversationKey);
        }
        return null;
    }

    private Utils() {

    }
}
