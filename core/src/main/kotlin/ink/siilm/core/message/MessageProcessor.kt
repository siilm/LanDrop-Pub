package ink.siilm.core.message

import ink.siilm.proto.InternalComm

/**
 * 消息处理器接口（策略模式）。
 * 可插入：敏感词过滤、内容日志、速率限制等。
 */
interface MessageProcessor {
    /**
     * @return true 表示继续投递，false 表示拦截
     */
    suspend fun process(delivery: InternalComm.MessageDelivery): Boolean
}