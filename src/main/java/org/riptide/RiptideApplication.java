/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide;

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
        SpringApplication.run(RiptideApplication.class, args);
    }
}
