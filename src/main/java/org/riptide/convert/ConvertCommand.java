/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code convert} subcommand. Runs with no Spring context, like
 * {@link org.riptide.provisioning.ProvisioningCommand}: it reads one legacy file, writes two
 * 0.9 documents, and never touches the collector's beans.
 *
 * <p>It resolves no secrets. Secret references are copied through as written, because the
 * converter has no business reaching Vault and a resolved value would be a cleartext
 * community written into a file the operator is about to commit.</p>
 */
public final class ConvertCommand {

    private ConvertCommand() {
    }

    /** True if {@code arg} names this subcommand. */
    public static boolean matches(final String arg) {
        return "convert".equals(arg);
    }

    public static int run(final String[] args) {
        return run(args, System.out, System.err);
    }

    /**
     * As {@link #run(String[])} but with explicit streams.
     *
     * <p>The emitted configuration goes to {@code out} and the summary to {@code err}, so
     * {@code riptide convert nodes.yaml > new.yaml} produces a usable file while the operator
     * still reads what changed. With {@code --out-config} and {@code --out-inventory} the two
     * documents are written separately and the summary moves to {@code out}.</p>
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) {
        if (args.length < 2) {
            usage(err);
            return 2;
        }
        Path configOut = null;
        Path inventoryOut = null;
        Path input = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--force" -> {
                    force = true;
                }
                case "-h", "--help" -> {
                    usage(out);
                    return 0;
                }
                case "--out-config" -> {
                    if (++i >= args.length) {
                        err.println("error: --out-config needs a path");
                        return 2;
                    }
                    configOut = Path.of(args[i]);
                }
                case "--out-inventory" -> {
                    if (++i >= args.length) {
                        err.println("error: --out-inventory needs a path");
                        return 2;
                    }
                    inventoryOut = Path.of(args[i]);
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        // an unknown flag would otherwise become the input path, and the
                        // operator would be told their typo'd option is an unreadable file
                        err.println("error: unknown option " + args[i]);
                        usage(err);
                        return 2;
                    }
                    if (input != null) {
                        err.println("error: only one legacy file can be converted at a time");
                        usage(err);
                        return 2;
                    }
                    input = Path.of(args[i]);
                }
            }
        }
        if (input == null) {
            usage(err);
            return 2;
        }

        final String content;
        try {
            content = Files.readString(input);
        } catch (final IOException e) {
            err.println("error: cannot read " + input + ": " + e.getMessage());
            return 1;
        }

        final LegacyConverter.Converted converted;
        try {
            converted = LegacyConverter.convert(LegacyConfigReader.parse(content, input.toString()));
        } catch (final RuntimeException e) {
            // nothing is written on failure: a half-converted pair of files is worse than none,
            // because the operator cannot tell which half is trustworthy
            err.println("error: " + e.getMessage());
            return 1;
        }

        // checked before anything is written, so a refusal on the second path cannot leave
        // the first one already committed. The comment below promises both-or-neither and
        // the previous version only delivered it for conversion failures
        if (!force) {
            for (final Path target : new Path[] {configOut, inventoryOut}) {
                if (target != null && Files.exists(target)) {
                    err.println("error: " + target + " already exists. The emitted config is a "
                            + "fragment, not a whole application.yaml, so writing over one would "
                            + "destroy the rest of it. Choose another path, or pass --force.");
                    return 1;
                }
            }
        }
        // both or neither, for real. An existence check is not enough: the second path can
        // fail for a missing directory or a permission, and the first is already committed by
        // then. Anything written before the failure is removed
        final java.util.List<Path> written = new java.util.ArrayList<>();
        try {
            if (configOut != null) {
                Files.writeString(configOut, converted.mainConfig());
                written.add(configOut);
            }
            if (inventoryOut != null) {
                Files.writeString(inventoryOut, converted.inventory());
                written.add(inventoryOut);
            }
        } catch (final IOException e) {
            err.println("error: cannot write output: " + e.getMessage());
            for (final Path partial : written) {
                try {
                    Files.deleteIfExists(partial);
                } catch (final IOException cleanup) {
                    err.println("error: " + partial + " was written before the failure and could "
                            + "not be removed: " + cleanup.getMessage());
                }
            }
            return 1;
        }

        if (configOut == null) {
            out.print(converted.mainConfig());
        }
        if (inventoryOut == null) {
            if (configOut == null) {
                // one stream, two documents: separated so the operator can see where to split,
                // and each carries a header naming the file it belongs in
                out.println("---");
            }
            out.print(converted.inventory());
        }

        // the summary shares stdout only when neither document is going there, so a redirect
        // of stdout never picks up prose
        final PrintStream summaryStream = configOut != null && inventoryOut != null ? out : err;
        converted.summary().forEach(summaryStream::println);
        if (configOut != null) {
            summaryStream.println("Wrote credential sets and polling profiles to " + configOut);
        }
        if (inventoryOut != null) {
            summaryStream.println("Wrote agent ranges and enrichment entries to " + inventoryOut);
        }
        // the emitted files are valid, but following them without this step is not: both
        // checks fail or warn at 0.9 startup, and the converter is the only place that knows
        // the operator still has these keys, because it just read them
        summaryStream.println("Before starting 0.9, remove 'riptide.nodes' from your application "
                + "config, and the retired 'riptide.snmp.poll.refresh-interval-ms' and "
                + "'.snapshot-expiry-ms' keys if you had them: the first warns at startup and "
                + "the second fails it.");
        return 0;
    }

    private static void usage(final PrintStream err) {
        err.println("usage: riptide convert <legacy-config.yaml> [--out-config <path>] "
                + "[--out-inventory <path>] [--force]");
        err.println();
        err.println("  Converts a 0.8 riptide.nodes configuration into 0.9 form. Emits two");
        err.println("  documents: credential sets and polling profiles for the main config, and");
        err.println("  agent ranges and enrichment entries for the inventory file.");
        err.println();
        err.println("  Without --out flags both go to stdout separated by '---', and the summary");
        err.println("  goes to stderr so the output can be redirected.");
    }
}
