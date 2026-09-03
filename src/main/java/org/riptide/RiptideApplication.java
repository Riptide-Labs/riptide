/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide;

import org.riptide.convert.ConvertCommand;
import org.riptide.provisioning.ProvisioningCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.PrintStream;
import java.util.OptionalInt;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RiptideApplication {
    public static void main(final String... args) {
        final OptionalInt exit = dispatchContextFree(args, System.out, System.err);
        if (exit.isPresent()) {
            System.exit(exit.getAsInt());
        }
        SpringApplication.run(RiptideApplication.class, args);
    }

    /**
     * Runs {@code args[0]} if it names a subcommand that works without a Spring context, and
     * returns its exit code; empty means this invocation starts the collector instead.
     *
     * <p>Admin provisioning subcommands run this way because there are no collector beans and no
     * admin capability in the running daemon. The upgrade converter runs this way for a different
     * reason with the same shape: it must work against a 0.8 configuration the 0.9 collector
     * refuses to start on.</p>
     *
     * <p>Which means neither of them loads {@code logback-spring.xml} — Spring Boot loads it,
     * Logback does not — so Logback falls back to a console appender on stdout, the stream
     * {@code convert} writes the generated configuration to. The routing is installed here, before
     * either subcommand runs: an appender caches its stream when it starts, so a record already
     * emitted cannot be moved afterwards. One call site covers both, so a third context-free
     * subcommand cannot arrive with only half the fix.</p>
     *
     * <p>The streams are parameters rather than {@code System.out}/{@code System.err} read inside,
     * so a test drives the same dispatch {@code main} does and can assert which stream a record
     * reached. {@code RiptideApplicationTest} is what pins that this method routes at all, and
     * that it routes before it dispatches.</p>
     */
    static OptionalInt dispatchContextFree(final String[] args, final PrintStream out,
                                           final PrintStream diagnostics) {
        final String subcommand = args.length > 0 ? args[0] : "";
        final boolean provisioning = ProvisioningCommand.matches(subcommand);
        if (!provisioning && !ConvertCommand.matches(subcommand)) {
            return OptionalInt.empty();
        }
        CliLogging.routeTo(diagnostics);
        return OptionalInt.of(provisioning
                ? ProvisioningCommand.run(args, out, diagnostics)
                : ConvertCommand.run(args, out, diagnostics));
    }
}
