/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a JSON-RPC 2.0 message frame for the Model Context Protocol.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcMessage {

    @Builder.Default
    private String jsonrpc = "2.0";

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
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;
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
