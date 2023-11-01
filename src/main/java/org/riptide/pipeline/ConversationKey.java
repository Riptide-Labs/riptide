package org.riptide.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.StringWriter;
import java.util.Objects;

/**
 * Contains all of the fields used to uniquely identify a conversation.
 */
@ToString
@EqualsAndHashCode
@Getter
@AllArgsConstructor
public class ConversationKey {

    private final String location;
    private final Integer protocol;
    private final String lowerIp;
    private final String upperIp;
    private final String application;

    /**
     * Utility class for building the {@link ConversationKey} and
     * converting it to/from a string so that it can be used in
     * group-by statements when querying.
     *
     * These methods are optimized for speed when generating the key,
     * with the constraint that we must also be able to decode the key.
     *
     * @author jwhite
     */
    public static class Utils {
        private static final Gson gson = new GsonBuilder().create();

        // TODO MVR remove gson
        public static ConversationKey fromJsonString(String json) {
            final Object[] array = gson.fromJson(json, Object[].class);
            if (array.length != 5) {
                throw new IllegalArgumentException("Invalid conversation key string: " + json);
            }
            return new ConversationKey((String) array[0], ((Number) array[1]).intValue(),
                    (String) array[2], (String) array[3], (String) array[4]);
        }

        public static String getConvoKeyAsJsonString(final String location,
                                                     final Integer protocol,
                                                     final String srcAddr,
                                                     final String dstAddr,
                                                     final String application) {
            // Only generate the key if all of the required fields are set
            if (location != null
                    && protocol != null
                    && srcAddr != null
                    && dstAddr != null) {
                // Build the JSON string manually
                // This is faster than creating some new object on which we can use gson.toJson or similar
                final StringWriter writer = new StringWriter();
                writer.write("[");

                // Use GSON to encode the location, since this may contain characters that need to be escape
                writer.write(gson.toJson(location));
                writer.write(",");
                writer.write(Integer.toString(protocol));
                writer.write(",");

                // Write out addresses in canonical format (lower one first)
                if (Objects.compare(srcAddr, dstAddr, String::compareTo) < 0) {
                    writer.write(gson.toJson(srcAddr));
                    writer.write(",");
                    writer.write(gson.toJson(dstAddr));
                } else {
                    writer.write(gson.toJson(dstAddr));
                    writer.write(",");
                    writer.write(gson.toJson(srcAddr));
                }
                writer.write(",");

                if (application != null) {
                    writer.write(gson.toJson(application));
                } else {
                    writer.write("null");
                }

                writer.write("]");
                return writer.toString();
            }
            return null;
        }
    }
}
