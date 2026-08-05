/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Clients put their own members on a frame and later protocol revisions add them. Rejecting the
     * frame over a member this server has no use for would fail requests it can answer perfectly
     * well, so the tolerance is asserted against a default mapper rather than left to whichever
     * mapper happens to be injected.
     */
    @Test
    void parsesFramesCarryingUnknownMembers() throws Exception {
        final String frame = """
                {"jsonrpc":"2.0","id":1,"method":"ping","params":{},
                 "clientInfo":{"name":"some-client"},"traceId":"abc"}
                """;

        final JsonRpcMessage message = objectMapper.readValue(frame, JsonRpcMessage.class);

        assertThat(message.getId()).isEqualTo(1);
        assertThat(message.getMethod()).isEqualTo("ping");
    }

    @Test
    void parsesErrorFramesCarryingUnknownMembers() throws Exception {
        final String frame =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\",\"detail\":\"x\"}}";

        final JsonRpcMessage message = objectMapper.readValue(frame, JsonRpcMessage.class);

        assertThat(message.getError().getCode()).isEqualTo(-32601);
        assertThat(message.getError().getMessage()).isEqualTo("nope");
    }

    /** Absent members stay off the wire: a result frame must not carry a null "error", and vice versa. */
    @Test
    void omitsAbsentMembersWhenSerialising() throws Exception {
        final String result = objectMapper.writeValueAsString(JsonRpcMessage.createResult(1, java.util.Map.of()));

        assertThat(result).contains("\"jsonrpc\":\"2.0\"").contains("\"id\":1");
        assertThat(result).doesNotContain("\"error\"").doesNotContain("\"method\"");
    }
}
