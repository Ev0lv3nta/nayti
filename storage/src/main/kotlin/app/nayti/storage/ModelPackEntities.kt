package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "model_pack",
    primaryKeys = ["packId", "packVersion"],
    indices = [
        Index(value = ["manifestSha256"], unique = true),
        Index(value = ["relativeDirectory"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ModelPackEntity(
    val packId: String,
    val packVersion: String,
    val keyId: String,
    val manifestSha256: String,
    val relativeDirectory: String,
    val payloadBytes: Long,
    val installedAtMillis: Long,
    val status: String,
)

object ModelPackStatus {
    const val INSTALLED_CANDIDATE = "INSTALLED_CANDIDATE"
}
