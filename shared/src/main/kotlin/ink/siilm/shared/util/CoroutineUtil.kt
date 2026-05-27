package ink.siilm.shared.util

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * 协程工具 — 自定义 CoroutineScope 工厂。
 *
 * 原则：
 * - 不使用 GlobalScope
 * - 使用 SupervisorJob 确保子协程失败不影响兄弟协程
 * - 为每个 Scope 设置 CoroutineName 便于调试
 */
object CoroutineUtil {

    /**
     * 创建应用级协程作用域。
     * 使用 SupervisorJob + Dispatchers.Default，适用于应用主生命周期。
     */
    fun createAppScope(name: String = "landrop-app"): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName(name)
        )
    }

    /**
     * 创建 I/O 密集型协程作用域。
     * 用于文件读写、数据库操作等阻塞 I/O 任务。
     */
    fun createIOScope(name: String = "landrop-io"): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName(name)
        )
    }

    /**
     * 创建 CPU 密集型协程作用域。
     * 用于 Protobuf 编解码等计算任务。
     */
    fun createComputeScope(name: String = "landrop-compute"): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName(name)
        )
    }

    private val log = LoggerFactory.getLogger(CoroutineUtil::class.java)
}