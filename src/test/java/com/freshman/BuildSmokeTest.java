package com.freshman;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冒烟测试：仅证明测试设施（surefire + JUnit5）可用，无业务含义。 */
class BuildSmokeTest {

    @Test
    void testInfrastructureWorks() {
        assertTrue(true, "测试设施应可用");
    }
}
