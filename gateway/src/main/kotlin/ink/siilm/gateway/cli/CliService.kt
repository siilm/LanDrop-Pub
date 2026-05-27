package ink.siilm.gateway.cli

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * CLI 交互服务。
 * 读取 stdin → 解析指令 → 确认（写操作）→ 执行 → 输出结果。
 */
class CliService(
    private val handlers: CliHandlers,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(CliService::class.java)
    private val scanner = Scanner(System.`in`)
    private val confirmRe = Regex("^[yY]$")

    fun startInteractive() {
        log.info("CLI interactive mode started. Type 'help' for commands, '/shutdown' to exit.")
        println("\nLanDrop CLI ready. Type a command:")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                print("> ")
                val input = scanner.nextLine().trim()
                if (input.isEmpty()) continue

                val cmd = CliCommand.parse(input)
                val result = execute(cmd)

                if (result != null) {
                    // 空字符串表示静默操作完成，不打印换行
                    if (result.isNotEmpty()) println(result)
                }

                // shutdown 后退出循环
                if (cmd is CliCommand.Shutdown) break
            }
            log.info("CLI session ended")
        }
    }

    /**
     * 执行指令，返回输出文本（null 表示不输出）。
     */
    fun execute(cmd: CliCommand): String? {
        return when (cmd) {
            is CliCommand.Stat     -> handlers.stat()
            is CliCommand.Help     -> helpText()
            is CliCommand.Unknown  -> "ERROR: unknown command '${cmd.input}'. Type 'help' for usage."

            is CliCommand.UserAdd  -> {
                val desc = if (cmd.userId != null) "Create user ${cmd.username} (userId=${cmd.userId})?" else "Create user ${cmd.username}?"
                confirm(desc, "useradd cancelled") { handlers.userAdd(cmd.username, cmd.userId) }
            }
            is CliCommand.UserDel  -> confirm("⚠ Delete user ${cmd.userId}? This cascades to all their data.", "userdel cancelled") { handlers.userDel(cmd.userId) }
            is CliCommand.UserMod  -> {
                val desc = when (cmd.action) {
                    "chown" -> "Transfer ownership from ${cmd.userId} to ${cmd.extra}"
                    else -> "Set ${cmd.userId} to ${cmd.action}${if (cmd.extra != null) " (${cmd.extra})" else ""}"
                }
                confirm("Modify user: $desc?", "usermod cancelled") { handlers.userMod(cmd.userId, cmd.action, cmd.extra) }
            }

            is CliCommand.RoomAdd  -> confirm("Create room ${cmd.roomId} (creater: ${cmd.createrId})?", "roomadd cancelled") { handlers.roomAdd(cmd.roomId, cmd.createrId) }
            is CliCommand.RoomDel  -> confirm("Delete room ${cmd.roomId}? All members and messages will be removed.", "roomdel cancelled") { handlers.roomDel(cmd.roomId) }
            is CliCommand.RoomMod  -> {
                val desc = when (cmd.action) {
                    "setchat" -> "set chat mode to ${if (cmd.extra == "0") "UNLIMIT" else "LIMIT"} for ${cmd.roomId}"
                    "setcreater" -> "set creater of ${cmd.roomId} to ${cmd.extra}"
                    else -> cmd.action
                }
                confirm("Modify room: $desc?", "roommod cancelled") { handlers.roomMod(cmd.roomId, cmd.action, cmd.extra) }
            }

            is CliCommand.FetchUser -> handlers.fetchUser(cmd.userName)
            is CliCommand.FetchRoom -> handlers.fetchRoom(cmd.roomName)

            is CliCommand.Shutdown -> {
                confirm("Shutdown server?", "shutdown cancelled") {
                    log.info("CLI: shutdown initiated")
                    scope.launch {
                        delay(500.milliseconds)
                        log.info("Server shutting down...")
                        exitProcess(0)
                    }
                    ""
                }
            }
        }
    }

    /**
     * 需要确认的写操作：提示 → 等待 y/N → 执行或取消。
     * @param prompt 确认提示文本
     * @param cancelMsg 取消时的消息
     * @param action 确认后执行的 lambda
     */
    private fun confirm(prompt: String, cancelMsg: String, action: () -> String): String {
        print("$prompt [y/N] ")
        val response = scanner.nextLine().trim()
        return if (confirmRe.matches(response)) {
            action()
        } else {
            cancelMsg
        }
    }

    // ═══════════════════════════════════════════════════════════

    private fun helpText(): String = buildString {
        appendLine("=== LanDrop CLI Commands ===")
        appendLine()
        appendLine("  stat                                          Show server status")
        appendLine("  useradd  <username> [userid]                  Create user (userId auto if omitted)")
        appendLine("  userdel  <user_id>                            Delete a user (cascade)")
        appendLine("  usermod  <user_id> pubadmin                   Promote to public admin")
        appendLine("  usermod  <user_id> member                     Demote to member")
        appendLine("  usermod  <user_id> admin  <room_id>           Set as room admin")
        appendLine("  usermod  <user_id> chown  <new_owner_id>      Transfer ownership")
        appendLine("  roomadd  <room_id> <creater_id>               Create a new room")
        appendLine("  roomdel  <room_id>                            Delete a room")
        appendLine("  roommod  <room_id> setchat    0|1             Set chat mode")
        appendLine("  roommod  <room_id> setcreater <user_id>       Change room creater")
        appendLine("  fetchuser <user_name>                         Search users by name")
        appendLine("  fetchroom <room_name>                         Search rooms by name")
        appendLine("  /shutdown                                     Graceful shutdown")
        appendLine("  help, ?                                       Show this help")
        appendLine()
        appendLine("  All write commands require [y/N] confirmation.")
    }
}
