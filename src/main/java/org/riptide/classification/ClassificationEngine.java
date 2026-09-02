/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import java.util.List;
import java.util.Optional;

public interface ClassificationEngine {

    /**
     * Notified after a reload has published a new ruleset. Registered through
     * {@link #addClassificationRulesReloadedListener}; nothing is replayed to a listener that registers after a
     * publish, which is what {@link #currentPublication()} is for.
     *
     * <p><b>The callback runs on the engine's single reload thread, and must return promptly.</b> It is invoked
     * from inside {@code reload()}, after the rules are published but before the reload is recorded as finished,
     * and every registered listener is delivered one after another on that same thread. So a callback that blocks
     * delays every later listener and the reload itself; a callback that never returns prevents the load from
     * <em>ever</em> settling, and on the initial load that parks every {@code classify()} caller for the life of
     * the process with no error, no counter and no gauge to say why. The publish path deliberately does not bound
     * a callback with a timeout or hand it to an executor — an implementer keeping this contract is what makes
     * that unnecessary. Do slow work by handing it to your own thread and returning.</p>
     *
     * <p>Throwing is safe, by contrast, and needs no defensive {@code try} of your own: anything a callback
     * throws — {@code Error} included — is logged at ERROR by the engine, the remaining listeners still receive
     * the publish, and the reload still succeeds.</p>
     *
     * <p>Callbacks may call back into the engine. Registering, deregistering and {@link #currentPublication()}
     * are all safe from inside one, because no lock is held across the call. {@link #getInvalidRules()} is
     * <b>not</b>: it waits for the initial load to settle, and on the initial load the callback is running on the
     * very thread that would settle it.</p>
     */
    interface ClassificationRulesReloadedListener {
        /**
         * @param rules the ruleset just published, rejected rules included, in evaluation order. Immutable. Which
         *     of them were rejected is not carried here — ask {@link #currentPublication()}.
         */
        void classificationRulesReloaded(List<Rule> rules);
    }

    /**
     * What one reload published: the ruleset it was handed, and the subset of it that was rejected and is
     * therefore classifying nothing. Both lists are immutable copies taken at publish time, so a holder of a
     * {@code Publication} keeps reading the ruleset it was given however many reloads follow.
     *
     * @param rules every rule in the published ruleset, rejected ones included, in evaluation order
     * @param invalidRules the rules in {@link #rules()} that could not be preprocessed and were ignored
     */
    record Publication(List<Rule> rules, List<Rule> invalidRules) {
        public Publication {
            rules = List.copyOf(rules);
            invalidRules = List.copyOf(invalidRules);
        }
    }

    String classify(ClassificationRequest classificationRequest);

    /**
     * The rejected rules of whatever is serving, waiting for the initial load to settle first and throwing if it
     * failed — the same posture as {@link #classify}.
     *
     * <p>It has no production caller since {@link #currentPublication()} arrived, and it stays anyway,
     * deliberately. It is public API on a public interface, its waiting behaviour is what a caller outside a
     * callback usually wants (an answer, or an exception, never "nothing yet"), and that behaviour is pinned by
     * existing tests. {@code currentPublication()} does not replace it: that one answers immediately and can
     * answer "nothing published", which is only the right shape for a caller that can act on it. Removing this
     * would be an API break bought for nothing.</p>
     */
    List<Rule> getInvalidRules();

    /**
     * The ruleset currently serving, or {@link Optional#empty()} when no load has ever published one.
     *
     * <p>This is the accessor a {@link ClassificationRulesReloadedListener} uses: the callback is handed only the
     * rules, and asking which of them were rejected is the reason the seam exists. It <b>never blocks and never
     * throws</b> — it is a single reference read — so it is safe to call from inside a callback, which
     * {@link #getInvalidRules()} is not: that one waits for the initial load to settle, and on the initial load
     * the caller <em>is</em> the thread that would settle it.</p>
     *
     * <p>The empty case is "nothing has been published yet", which is why this returns an {@code Optional} rather
     * than a list: a publication whose {@link Publication#invalidRules()} is empty is a ruleset that loaded
     * cleanly, and an accessor answering both with an empty list could not tell those apart.</p>
     *
     * <p>No callback is replayed to a listener that registered after a publish. Asking here is how such a
     * listener learns what the boot load published.</p>
     */
    Optional<Publication> currentPublication();

    void reload() throws InterruptedException;

    void addClassificationRulesReloadedListener(ClassificationRulesReloadedListener classificationRulesReloadedListener);

    void removeClassificationRulesReloadedListener(ClassificationRulesReloadedListener classificationRulesReloadedListener);
}
