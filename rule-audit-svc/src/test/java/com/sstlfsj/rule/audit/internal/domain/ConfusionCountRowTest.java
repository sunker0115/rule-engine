package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfusionCountRowTest {

    @Test
    void settersAndGetters_roundTrip() {
        ConfusionCountRow r = new ConfusionCountRow();
        r.setBucket("ALL");
        r.setDimKey("1001");
        r.setTp(15);
        r.setFp(10);
        r.setFiredTotal(30);

        assertEquals("ALL", r.getBucket());
        assertEquals("1001", r.getDimKey());
        assertEquals(15, r.getTp());
        assertEquals(10, r.getFp());
        assertEquals(30, r.getFiredTotal());
    }
}
