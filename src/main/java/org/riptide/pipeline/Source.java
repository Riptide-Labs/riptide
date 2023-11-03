package org.riptide.pipeline;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.net.InetAddress;

// TODO fooker: Do we need to source by SocketAddr?
@Getter
@Setter
@AllArgsConstructor
public class Source {
    @NonNull
    private final String location;

    @NonNull
    private final InetAddress exporterAddr;
}
