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

@SpringBootApplication
@ConfigurationPropertiesScan
public class RiptideApplication {
    public static void main(final String... args) {
        // Admin provisioning subcommands run without a Spring context (no collector beans, no admin
        // capability in the running daemon). Everything else starts the collector as before.
        if (args.length > 0 && ProvisioningCommand.matches(args[0])) {
            System.exit(ProvisioningCommand.run(args));
        }
        // The upgrade converter runs the same way, and for the same reason: it must work
        // against a 0.8 configuration that the 0.9 collector refuses to start on
        if (args.length > 0 && ConvertCommand.matches(args[0])) {
            System.exit(ConvertCommand.run(args));
        }
        SpringApplication.run(RiptideApplication.class, args);
    }
}
