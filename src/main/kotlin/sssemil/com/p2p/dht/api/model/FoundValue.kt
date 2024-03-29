package sssemil.com.p2p.dht.api.model

import sssemil.com.p2p.dht.util.toHexString
import java.util.*

data class FoundValue(var tokenReply: Double, val value: ByteArray) : TokenModel(tokenReply) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FoundValue

        if (!Arrays.equals(value, other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(value)
    }

    override fun toString(): String {
        return "FoundValue(token=$token, value=${value.toHexString()})"
    }
}
