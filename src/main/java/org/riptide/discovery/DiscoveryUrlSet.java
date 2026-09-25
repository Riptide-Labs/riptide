/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * The single gate for discovery: {@code riptide.discovery.url} set to a non-blank value, or
 * {@code riptide.discovery.urls} holding a non-blank entry.
 *
 * <p>{@code urls} is read through {@link Binder}, not by name: a list arrives as
 * {@code riptide.discovery.urls[0]} from YAML and as {@code RIPTIDE_DISCOVERY_URLS_0} from the
 * environment, and neither is a property called {@code riptide.discovery.urls}. What counts as set
 * is {@code DiscoveryConfig.hasEntry}, the same rule {@code DiscoveryConfig.endpoints()} applies.
 * Both keys set is a match here and a refusal there, so the refusal names both keys instead of
 * this gate silently picking one.</p>
 *
 * <p>A plain {@link SpringBootCondition} rather than {@code @ConditionalOnExpression}, because an
 * expression is the one shape of this rule that can be broken by the value it reads.
 * {@code "'${riptide.discovery.url:}'.trim() != ''"} resolves the placeholder into the expression
 * <em>text</em> before SpEL parses it, unescaped, so an operator's {@code http://host/o'brien}
 * becomes the malformed literal {@code 'http://host/o'brien'} and the context refresh dies with a
 * {@code SpelParseException}. Naming the property inside {@code environment.getProperty(...)} fixes
 * the injection but not the whole problem: conditions on a class registered before {@code refresh()}
 * — which is what {@code ApplicationContextRunner} does — are evaluated before the {@code
 * environment} bean exists, so the expression fails to resolve its root instead. This class has no
 * expression surface at all and reads the environment the condition API hands it, which exists in
 * both orders.</p>
 *
 * <p>Blank counts as unset because a container image or a Helm template exports every variable it
 * knows about, value or not. {@code RIPTIDE_DISCOVERY_URL=""} must not turn discovery on for an
 * operator who never asked for it and then refuse to boot on an unusable URL.</p>
 *
 * <p>Extending {@link SpringBootCondition} rather than implementing {@code Condition} directly
 * costs nothing here and buys the condition evaluation report: {@code SpringBootCondition.matches()}
 * is what records a {@link ConditionOutcome} for {@code --debug} output and
 * {@code /actuator/conditions}, so an operator asking "why did discovery not turn on" gets an answer
 * instead of a silent no-match.</p>
 */
class DiscoveryUrlSet extends SpringBootCondition {

    /** Kept on one line, and the one literal in this gate, so it stays greppable. */
    static final String URL_PROPERTY = "riptide.discovery.url";

    /** The list form, kept on one line for the same reason. */
    static final String URLS_PROPERTY = "riptide.discovery.urls";

    @Override
    public ConditionOutcome getMatchOutcome(final ConditionContext context,
                                             final AnnotatedTypeMetadata metadata) {
        final ConditionMessage.Builder message = ConditionMessage.forCondition("Discovery URL");
        final String url = context.getEnvironment().getProperty(URL_PROPERTY);
        if (StringUtils.hasText(url)) {
            return ConditionOutcome.match(message.because(URL_PROPERTY + " is set and non-blank"));
        }
        final List<String> urls = Binder.get(context.getEnvironment())
                .bind(URLS_PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of());
        if (DiscoveryConfig.hasEntry(urls)) {
            return ConditionOutcome.match(message.because(URLS_PROPERTY + " holds a non-blank entry"));
        }
        return ConditionOutcome.noMatch(message.because(
                URL_PROPERTY + " is not set, or is blank, and " + URLS_PROPERTY + " holds no non-blank entry"));
    }
}
