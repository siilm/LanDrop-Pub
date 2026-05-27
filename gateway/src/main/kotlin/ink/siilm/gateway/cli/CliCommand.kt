package ink.siilm.gateway.cli

/**
 * CLI 指令定义与解析。
 */
sealed class CliCommand {
    data object Stat : CliCommand()
    data class UserAdd(val username: String, val userId: String? = null) : CliCommand()
    data class UserDel(val userId: String) : CliCommand()
    data class UserMod(val userId: String, val action: String, val extra: String? = null) : CliCommand()
    data class RoomAdd(val roomId: String, val createrId: String) : CliCommand()
    data class RoomDel(val roomId: String) : CliCommand()
    data class RoomMod(val roomId: String, val action: String, val extra: String? = null) : CliCommand()
    data class FetchUser(val userName: String) : CliCommand()
    data class FetchRoom(val roomName: String) : CliCommand()
    data object Shutdown : CliCommand()
    data class Unknown(val input: String) : CliCommand()
    data object Help : CliCommand()

    companion object {
        private val USER_ID_RE = Regex("^[A-Za-z0-9]{1,12}$")
        private val ROOM_ID_RE = Regex("^[A-Z0-9]{1,6}$")

        fun parse(input: String): CliCommand {
            val parts = input.trim().split("\\s+".toRegex())
            if (parts.isEmpty()) return Help
            return when (parts[0].lowercase()) {
                "stat"     -> if (parts.size == 1) Stat else Unknown(input)
                "useradd"  -> {
                    when {
                        parts.size == 2 -> {
                            if (parts[1].isBlank() || parts[1].length > 25) Unknown(input)
                            else UserAdd(parts[1])
                        }
                        parts.size == 3 -> {
                            if (parts[1].isBlank() || parts[1].length > 25) Unknown(input)
                            else if (!USER_ID_RE.matches(parts[2])) Unknown(input)
                            else UserAdd(parts[1], parts[2])
                        }
                        else -> Unknown(input)
                    }
                }
                "userdel"  -> {
                    if (parts.size != 2 || !USER_ID_RE.matches(parts[1])) Unknown(input)
                    else UserDel(parts[1])
                }
                "usermod"  -> {
                    if (parts.size < 3 || !USER_ID_RE.matches(parts[1])) return Unknown(input)
                    val action = parts[2].lowercase()
                    when {
                        action == "pubadmin" && parts.size == 3 -> UserMod(parts[1], "pubadmin")
                        action == "member"   && parts.size == 3 -> UserMod(parts[1], "member")
                        action == "admin"    && parts.size == 4 && ROOM_ID_RE.matches(parts[3]) -> UserMod(parts[1], "admin", parts[3])
                        action == "chown"    && parts.size == 4 && USER_ID_RE.matches(parts[3]) -> UserMod(parts[1], "chown", parts[3])
                        else -> Unknown(input)
                    }
                }
                "roomadd"  -> {
                    if (parts.size != 3 || !ROOM_ID_RE.matches(parts[1]) || !USER_ID_RE.matches(parts[2])) Unknown(input)
                    else RoomAdd(parts[1], parts[2])
                }
                "roomdel"  -> {
                    if (parts.size != 2 || !ROOM_ID_RE.matches(parts[1])) Unknown(input)
                    else RoomDel(parts[1])
                }
                "roommod"  -> {
                    if (parts.size < 3 || !ROOM_ID_RE.matches(parts[1])) return Unknown(input)
                    when (parts[2].lowercase()) {
                        "setchat"    -> if (parts.size == 4 && parts[3] in setOf("0", "1")) RoomMod(parts[1], "setchat", parts[3]) else Unknown(input)
                        "setcreater" -> if (parts.size == 4 && USER_ID_RE.matches(parts[3])) RoomMod(parts[1], "setcreater", parts[3]) else Unknown(input)
                        else -> Unknown(input)
                    }
                }
                "fetchuser" -> {
                    if (parts.size < 2) return Unknown(input)
                    FetchUser(parts.drop(1).joinToString(" "))
                }
                "fetchroom" -> {
                    if (parts.size < 2) return Unknown(input)
                    FetchRoom(parts.drop(1).joinToString(" "))
                }
                "/shutdown" -> if (parts.size == 1) Shutdown else Unknown(input)
                "help", "?" -> Help
                else        -> Unknown(input)
            }
        }
    }
}
