/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SecretRefTest {

    @Test
    public void parsesEnvRef() {
        final SecretRef ref = new SecretRef("env://RIPTIDE_SNMP_COMMUNITY");
        assertThat(ref.getScheme()).isEqualTo("env");
        assertThat(ref.getValue()).isEqualTo("RIPTIDE_SNMP_COMMUNITY");
        assertThat(ref.getKey()).isNull();
    }

    @Test
    public void parsesFileRef() {
        final SecretRef ref = new SecretRef("file:///run/secrets/community");
        assertThat(ref.getScheme()).isEqualTo("file");
        assertThat(ref.getValue()).isEqualTo("/run/secrets/community");
        assertThat(ref.getKey()).isNull();
    }

    @Test
    public void parsesFileRefWithKey() {
        final SecretRef ref = new SecretRef("file:///etc/riptide/sec.properties#snmp.community");
        assertThat(ref.getScheme()).isEqualTo("file");
        assertThat(ref.getValue()).isEqualTo("/etc/riptide/sec.properties");
        assertThat(ref.getKey()).isEqualTo("snmp.community");
    }

    @Test
    public void fallsBackToPlainForUnschemedValues() {
        final SecretRef ref = new SecretRef("c0mmunity");
        assertThat(ref.getScheme()).isEqualTo(SecretRef.SCHEME_PLAIN);
        assertThat(ref.getValue()).isEqualTo("c0mmunity");
        assertThat(ref.getKey()).isNull();
    }

    @Test
    public void ofIsNullSafe() {
        assertThat(SecretRef.of(null)).isNull();
        assertThat(SecretRef.of("env://X")).isNotNull();
    }

    @Test
    public void rejectsBlankAndValuelessRefs() {
        assertThatThrownBy(() -> new SecretRef("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretRef("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretRef("env://")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void toStringNeverExposesPlainSecrets() {
        assertThat(new SecretRef("s3cr3t").toString()).doesNotContain("s3cr3t");
        assertThat(new SecretRef("env://NAME").toString()).isEqualTo("env://NAME");
        assertThat(new SecretRef("file:///p#k").toString()).isEqualTo("file:///p#k");
    }
}
