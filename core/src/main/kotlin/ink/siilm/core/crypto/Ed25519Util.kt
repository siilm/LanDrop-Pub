package ink.siilm.core.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 非对称签名验证工具。
 *
 * 服务端仅持公钥，用于验证客户端私钥签名。
 * 使用 JDK 15+ 内置的 Ed25519 算法。
 */
object Ed25519Util {

    private const val ALGORITHM = "Ed25519"

    /**
     * 验证 Ed25519 签名。
     *
     * @param publicKeyBase64 客户端公钥（Base64 编码）
     * @param data 签名原文
     * @param signatureBase64 客户端签名（Base64 编码）
     * @return true 表示签名有效
     */
    fun verify(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val publicKey = KeyFactory.getInstance(ALGORITHM).generatePublic(keySpec)

            val sig = Signature.getInstance(ALGORITHM)
            sig.initVerify(publicKey)
            sig.update(data)

            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            sig.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 构造签名原文（与客户端约定的拼接顺序）:
     *   data = challenge || compact_json(device_info) || user_id
     */
    /**
     * 生成 Ed25519 密钥对。
     * @return Pair(公钥Base64, 私钥Base64)
     */
    fun generateKeyPair(): Pair<String, String> {
        val keyPair = java.security.KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        return Pair(publicKeyBase64, privateKeyBase64)
    }

    fun buildSignatureData(
        challenge: ByteArray,
        deviceInfoJson: ByteArray,
        userId: ByteArray
    ): ByteArray {
        return challenge + deviceInfoJson + userId
    }
}
