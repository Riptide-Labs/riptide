/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a JSON-RPC 2.0 message frame for the Model Context Protocol.
 *
 * <p>Unknown members are ignored rather than rejected. Clients put their own members on a frame and
 * later protocol revisions add them; failing the parse would turn a field this server simply has no
 * use for into a rejected request. Declared here rather than left to the mapper's configuration, so
 * the frame parses the same way whichever {@code ObjectMapper} reads it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcMessage {

    @Builder.Default
    private String jsonrpc = "2.0";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object id;

    private String method;
    private Map<String, Object> params;
    private Object result;
    private JsonRpcError error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;
    }

    public static JsonRpcMessage createRequest(final Object id, final String method, final Map<String, Object> params) {
        return JsonRpcMessage.builder()
                .id(id)
                .method(method)
                .params(params)
                .build();
    }

    public static JsonRpcMessage createError(final Object id, final int code, final String message) {
        return JsonRpcMessage.builder()
                .id(id)
                .error(JsonRpcError.builder().code(code).message(message).build())
                .build();
    }

    public static JsonRpcMessage createResult(final Object id, final Object result) {
        return JsonRpcMessage.builder()
                .id(id)
                .result(result)
                .build();
    }
}
