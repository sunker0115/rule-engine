package com.sstlfsj.rule.kernel.internal.condition.time;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class TimeZoneResolverTest {

    @Test
    void paramsTimezone_takesPriority() {
        assertThat(TimeZoneResolver.resolve("Asia/Shanghai", "America/New_York"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void sceneDefault_usedWhenParamsNull() {
        assertThat(TimeZoneResolver.resolve(null, "Asia/Shanghai"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void utc_whenBothNull() {
        assertThat(TimeZoneResolver.resolve(null, null)).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void utc_whenParamsBlank() {
        assertThat(TimeZoneResolver.resolve("  ", null)).isEqualTo(ZoneOffset.UTC);
    }
}
