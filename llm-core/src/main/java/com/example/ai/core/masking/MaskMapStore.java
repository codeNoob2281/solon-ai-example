package com.example.ai.core.masking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 掩码映射表存储（服务端进程内）
 * <p>
 * 按「会话 id」隔离：Chat / SimpleAgent / ReAct Agent 的每次请求都会把
 * {@code ChatSession.ATTR_SESSIONID} 注入 toolContext，因此请求侧与工具侧都能取到同一个 id，
 * 从而共享同一份映射表。ReAct Agent 的工具调用路径不会传入 ChatRequest，
 * 故不能依赖 {@code session.attrs()}（其 ChatSession 在每次推理时重建），这里改用
 * 「会话 id → 映射表」的服务端存储，既满足隔离，也保证跨步骤一致。
 * <p>
 * 安全约束：映射表只存在于服务端内存，不进入 Prompt/toolContext、不下发客户端；
 * 存储为有界 LRU，避免无限增长。
 *
 * @author huang zifan
 * @since 1.0.0
 */
public class MaskMapStore {

    /** 默认最多保留的会话映射数 */
    private static final int DEFAULT_MAX_SESSIONS = 1024;

    private final int maxSessions;

    /** 会话 id → 映射表（访问序 LRU） */
    private final Map<String, MaskMap> store;

    /** 无法取得会话 id 时的线程级兜底，避免跨会话串档 */
    private final ThreadLocal<MaskMap> fallback = ThreadLocal.withInitial(MaskMap::new);

    public MaskMapStore() {
        this(DEFAULT_MAX_SESSIONS);
    }

    public MaskMapStore(int maxSessions) {
        this.maxSessions = maxSessions > 0 ? maxSessions : DEFAULT_MAX_SESSIONS;
        this.store = new LinkedHashMap<String, MaskMap>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, MaskMap> eldest) {
                return size() > MaskMapStore.this.maxSessions;
            }
        };
    }

    /**
     * 取得（或创建）指定会话的映射表
     *
     * @param sessionId 会话 id；为空时退回线程级兜底
     */
    public synchronized MaskMap get(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return fallback.get();
        }
        MaskMap map = store.get(sessionId);
        if (map == null) {
            map = new MaskMap();
            store.put(sessionId, map);
        }
        return map;
    }
}
