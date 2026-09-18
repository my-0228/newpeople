package com.freshman.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * VectorIndexLoader 单元测试。
 *
 * 验证本项目的硬性约定：**索引加载失败绝不阻断应用启动**。
 * 索引为空时检索层会自动转为纯关键词模式，因此"表还没建""Embedding 服务不可用"
 * 都不应让应用起不来。
 */
class VectorIndexLoaderTest {

    @Test
    void loadFailureIsSwallowedAndDoesNotBlockStartup() {
        VectorIndex failing = mock(VectorIndex.class);
        doThrow(new IllegalStateException("模拟：kb_chunk 表不存在")).when(failing).rebuild();
        KeywordRetriever failingKeyword = mock(KeywordRetriever.class);
        doThrow(new IllegalStateException("模拟：关键词语料加载失败")).when(failingKeyword).rebuild();

        VectorIndexLoader loader = new VectorIndexLoader(failing, failingKeyword);

        assertDoesNotThrow(() -> loader.run(null),
                "两个索引加载失败都必须被吞掉，不能阻断应用启动");
        verify(failing, times(1)).rebuild();
        verify(failingKeyword, times(1)).rebuild();
    }

    @Test
    void successfulLoadInvokesBothRebuildsOnce() {
        VectorIndex ok = mock(VectorIndex.class);
        when(ok.size()).thenReturn(42);
        KeywordRetriever kw = mock(KeywordRetriever.class);
        when(kw.size()).thenReturn(42);

        new VectorIndexLoader(ok, kw).run(null);

        verify(ok, times(1)).rebuild();
        verify(kw, times(1)).rebuild();
        verify(ok, atLeastOnce()).size();
        verify(kw, atLeastOnce()).size();
    }
}
