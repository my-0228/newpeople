package com.freshman.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagProperties 启动期校验的单元测试。
 * 关注点：非法配置必须在启动期被拒绝，且报错信息能指明是哪个配置项。
 * 纯单元测试（不启动 Spring 上下文、不连数据库）。
 */
class RagPropertiesTest {

    private RagProperties valid() {
        RagProperties p = new RagProperties();
        p.getChunk().setSize(400);
        p.getChunk().setOverlap(60);
        p.getChunk().setMinSize(30);
        p.setTopKVector(20);
        p.setTopKKeyword(20);
        p.setTopKFinal(5);
        p.setMinScore(0.35);
        p.setKeywordMinScore(0.25);
        p.setRelativeFloor(0.6);
        p.getEmbedding().setDimensions(1024);
        p.getEmbedding().setBatchSize(25);
        return p;
    }

    @Test
    void validConfigPasses() {
        assertDoesNotThrow(() -> valid().validate());
    }

    @Test
    void overlapNotSmallerThanSizeRejected() {
        RagProperties p = valid();
        p.getChunk().setOverlap(400);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, p::validate);
        assertTrue(e.getMessage().contains("overlap"), "报错信息要指明是哪个配置项：" + e.getMessage());
    }

    @Test
    void minSizeNotSmallerThanSizeRejected() {
        RagProperties p = valid();
        p.getChunk().setMinSize(500);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, p::validate);
        assertTrue(e.getMessage().contains("min-size"), "报错信息要指明是哪个配置项：" + e.getMessage());
    }

    @Test
    void finalTopKNotExceedingRecallTopKRejected() {
        RagProperties p = valid();
        p.setTopKFinal(50);
        assertThrows(IllegalArgumentException.class, p::validate);
    }

    @Test
    void relativeFloorOutOfRangeRejected() {
        RagProperties p = valid();
        p.setRelativeFloor(1.5);
        assertThrows(IllegalArgumentException.class, p::validate);
    }

    @Test
    void embeddingBatchSizeAboveProviderLimitRejected() {
        RagProperties p = valid();
        p.getEmbedding().setBatchSize(100);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, p::validate);
        assertTrue(e.getMessage().contains("batch-size"), "报错信息要指明是哪个配置项：" + e.getMessage());
    }
}
