package com.example.automation

import com.example.data.local.ApexDao
import com.example.data.local.AutomationVaultEntryEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages user-owned automation values (passwords, emails, phone numbers, usernames,
 * names, addresses, and custom values) encrypted at rest via [CredentialVaultCrypto].
 *
 * Values can be grabbed during a recording based on user selection, edited inside the
 * Automation Vault inside Automation, and resolved dynamically on every replay.
 */
class AutomationVaultManager(
    private val dao: ApexDao,
    val crypto: CredentialVaultCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val engine: AutomationEngine = AutomationEngine()
) {
    val entriesFlow: Flow<List<AutomationVaultEntryEntity>> = dao.observeAllAutomationVaultEntries()
    val countFlow: Flow<Int> = dao.observeAutomationVaultCount()

    suspend fun getAllEntriesOnce(): List<AutomationVaultEntryEntity> =
        dao.getAllAutomationVaultEntriesOnce()

    suspend fun getEntryById(entryId: String): AutomationVaultEntryEntity? =
        dao.getAutomationVaultEntryById(entryId.trim())

    suspend fun saveOrUpdateEntry(
        entryId: String? = null,
        category: AutomationVaultCategory,
        label: String,
        plainValue: String,
        websiteOrigin: String = "",
        isSensitive: Boolean = category.isSensitiveDefault,
        sourceWorkflowId: Long? = null
    ): Result<AutomationVaultEntryEntity> {
        val cleanLabel = label.trim().ifBlank { category.label }
        val cleanValue = plainValue
        if (cleanValue.isEmpty()) {
            return Result.failure(IllegalArgumentException("Vault value cannot be empty."))
        }
        val cleanOrigin = if (websiteOrigin.isBlank()) {
            ""
        } else {
            when (val parsed = engine.parseOrigin(websiteOrigin)) {
                is RecordingOriginResult.Valid -> parsed.canonicalOrigin
                else -> websiteOrigin.trim()
            }
        }
        val displayDomain = if (cleanOrigin.isBlank()) {
            "Global"
        } else {
            when (val parsed = engine.parseOrigin(cleanOrigin)) {
                is RecordingOriginResult.Valid -> parsed.displayOrigin.ifBlank { cleanOrigin }
                else -> cleanOrigin
            }
        }
        val existing = entryId?.trim()?.takeIf { it.isNotEmpty() }?.let { dao.getAutomationVaultEntryById(it) }
        val finalId = existing?.entryId ?: UUID.randomUUID().toString()
        val now = clock()
        val sensitive = isSensitive || category == AutomationVaultCategory.PASSWORD

        val chars = cleanValue.toCharArray()
        val encryptedPayload = try {
            when (val enc = crypto.encryptChars(chars)) {
                is VaultCryptoResult.Success -> enc.value
                is VaultCryptoResult.KeyInvalidated -> return Result.failure(IllegalStateException(enc.reason))
                is VaultCryptoResult.AuthenticationRequired -> return Result.failure(IllegalStateException(enc.reason))
                is VaultCryptoResult.CorruptedPayload -> return Result.failure(IllegalStateException(enc.reason))
                is VaultCryptoResult.CryptoFailure -> return Result.failure(IllegalStateException(enc.reason))
            }
        } finally {
            chars.fill('\u0000')
        }

        val entity = AutomationVaultEntryEntity(
            entryId = finalId,
            category = category.id,
            label = cleanLabel,
            websiteOrigin = cleanOrigin,
            domain = displayDomain,
            encryptedValue = encryptedPayload,
            maskedPreview = buildMaskedPreview(category, cleanValue, sensitive),
            isSensitive = sensitive,
            sourceWorkflowId = sourceWorkflowId ?: existing?.sourceWorkflowId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = existing?.lastUsedAt
        )
        dao.upsertAutomationVaultEntry(entity)
        return Result.success(entity)
    }

    suspend fun deleteEntry(entryId: String) {
        val cleanId = entryId.trim()
        if (cleanId.isNotEmpty()) {
            dao.deleteAutomationVaultEntryById(cleanId)
        }
    }

    suspend fun decryptEntryValue(entryId: String, markUsed: Boolean = false): Result<String> {
        val cleanId = entryId.trim()
        if (cleanId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Automation Vault entry ID is missing."))
        }
        val entity = dao.getAutomationVaultEntryById(cleanId)
            ?: return Result.failure(NoSuchElementException("Automation Vault entry not found."))
        val decrypted = when (val res = crypto.decrypt(entity.encryptedValue)) {
            is VaultCryptoResult.Success -> res.value
            is VaultCryptoResult.KeyInvalidated -> return Result.failure(IllegalStateException(res.reason))
            is VaultCryptoResult.AuthenticationRequired -> return Result.failure(IllegalStateException(res.reason))
            is VaultCryptoResult.CorruptedPayload -> return Result.failure(IllegalStateException(res.reason))
            is VaultCryptoResult.CryptoFailure -> return Result.failure(IllegalStateException(res.reason))
        }
        if (markUsed) {
            dao.markAutomationVaultEntryUsed(cleanId, clock())
        }
        return Result.success(decrypted)
    }

    suspend fun importSelectedCandidates(
        candidates: List<VaultFileImportCandidate>
    ): Result<List<AutomationVaultEntryEntity>> {
        val selected = candidates.filter { it.isSelected && it.value.isNotEmpty() }
        if (selected.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one value from the file to save."))
        }
        val saved = mutableListOf<AutomationVaultEntryEntity>()
        for (candidate in selected) {
            val res = saveOrUpdateEntry(
                entryId = candidate.existingEntryId,
                category = candidate.category,
                label = candidate.label,
                plainValue = candidate.value,
                websiteOrigin = candidate.websiteOrigin,
                isSensitive = candidate.isSensitive || candidate.category == AutomationVaultCategory.PASSWORD
            )
            if (res.isFailure) {
                return Result.failure(
                    res.exceptionOrNull() ?: IllegalStateException("Failed saving '${candidate.label}' to Automation Vault.")
                )
            }
            res.getOrNull()?.let { saved.add(it) }
        }
        return Result.success(saved)
    }

    companion object {
        private val EMAIL_REGEX = Regex("""^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")
        private val PHONE_VALUE_REGEX = Regex("""^[+\d][\d\s().-]{5,18}\d$""")

        fun buildMaskedPreview(
            category: AutomationVaultCategory,
            rawValue: String,
            isSensitive: Boolean
        ): String {
            if (isSensitive || category == AutomationVaultCategory.PASSWORD) {
                return "••••••••"
            }
            val clean = rawValue.trim()
            if (clean.length <= 2) return clean
            return clean.take(64)
        }

        fun detectCategory(
            targetLabel: String,
            selector: String,
            value: String = "",
            isCredentialStep: Boolean = false,
            credentialFieldRole: CredentialFieldRole? = null,
            inputTypeHint: String = ""
        ): AutomationVaultCategory {
            val combined = "$targetLabel $selector $inputTypeHint".lowercase()
            if (isCredentialStep ||
                credentialFieldRole == CredentialFieldRole.PASSWORD ||
                inputTypeHint.equals("password", ignoreCase = true) ||
                selector.contains("""type="password"""", ignoreCase = true) ||
                selector.contains("type='password'", ignoreCase = true) ||
                Regex("""\b(password|passcode|passphrase|pin|pwd|otp|verification code|one-time)\b""").containsMatchIn(combined)
            ) {
                return AutomationVaultCategory.PASSWORD
            }
            if (inputTypeHint.equals("email", ignoreCase = true) ||
                selector.contains("""type="email"""", ignoreCase = true) ||
                selector.contains("type='email'", ignoreCase = true) ||
                combined.contains("email") ||
                combined.contains("e-mail") ||
                EMAIL_REGEX.matches(value.trim())
            ) {
                return AutomationVaultCategory.EMAIL
            }
            if (inputTypeHint.equals("tel", ignoreCase = true) ||
                selector.contains("""type="tel"""", ignoreCase = true) ||
                selector.contains("type='tel'", ignoreCase = true) ||
                Regex("""\b(phone|mobile|tel|telephone|cell|whatsapp|sms|contact number)\b""").containsMatchIn(combined) ||
                PHONE_VALUE_REGEX.matches(value.trim())
            ) {
                return AutomationVaultCategory.PHONE
            }
            if (credentialFieldRole == CredentialFieldRole.USERNAME ||
                Regex("""\b(username|user name|userid|user_id|login|handle|account)\b""").containsMatchIn(combined)
            ) {
                return AutomationVaultCategory.USERNAME
            }
            if (Regex("""\b(address|street|city|postal|zip|zipcode|postcode|apartment)\b""").containsMatchIn(combined)) {
                return AutomationVaultCategory.ADDRESS
            }
            if (Regex("""\b(name|first name|firstname|first_name|last name|lastname|last_name|full name|fullname|given name|family name)\b""").containsMatchIn(combined)) {
                return AutomationVaultCategory.NAME
            }
            return AutomationVaultCategory.CUSTOM
        }

        fun extractRecordingCandidates(
            steps: List<AutomationStep>,
            capturedSecretsByStepId: Map<String, String> = emptyMap(),
            existingEntries: List<AutomationVaultEntryEntity> = emptyList(),
            sourceUrl: String = ""
        ): List<RecordingVaultCandidate> {
            val engine = AutomationEngine()
            val canonicalOrigin = if (sourceUrl.isBlank()) {
                ""
            } else {
                engine.parseOrigin(sourceUrl).canonicalOrigin
            }
            val domain = if (sourceUrl.isBlank()) "" else engine.extractDomain(sourceUrl)
            val eligibleSteps = steps.filter { step ->
                step.actionType == AutomationActionType.INPUT ||
                    step.actionType == AutomationActionType.SELECT ||
                    step.actionType == AutomationActionType.FILL_CREDENTIAL ||
                    step.isCredentialStep
            }
            return eligibleSteps.mapNotNull { step ->
                val secretOverride = capturedSecretsByStepId[step.id].orEmpty()
                val rawVal = if (step.isCredentialStep || step.value.isBlank()) {
                    secretOverride
                } else {
                    step.value.trim()
                }
                val category = if (step.vaultCategory.isNotBlank()) {
                    AutomationVaultCategory.fromId(step.vaultCategory)
                } else {
                    detectCategory(
                        targetLabel = step.targetLabel,
                        selector = step.selector,
                        value = rawVal,
                        isCredentialStep = step.isCredentialStep,
                        credentialFieldRole = step.credentialFieldRole
                    )
                }
                val isPwd = category == AutomationVaultCategory.PASSWORD || step.isCredentialStep
                if (!isPwd && rawVal.isBlank()) {
                    return@mapNotNull null
                }
                val cleanTarget = step.targetLabel
                    .replace(Regex("""\s+field$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { category.label }
                val defaultLabel = if (domain.isNotBlank() && domain != "Start Page") {
                    "$cleanTarget ($domain)"
                } else {
                    cleanTarget
                }
                val matchedExisting = step.vaultEntryId?.let { boundId ->
                    existingEntries.firstOrNull { it.entryId == boundId }
                } ?: existingEntries.firstOrNull { existing ->
                    existing.category.equals(category.id, ignoreCase = true) &&
                        (existing.websiteOrigin.isBlank() ||
                            canonicalOrigin.isBlank() ||
                            existing.websiteOrigin == canonicalOrigin) &&
                        existing.label.equals(defaultLabel, ignoreCase = true)
                }
                RecordingVaultCandidate(
                    stepId = step.id,
                    stepOrder = step.order,
                    targetLabel = cleanTarget,
                    selector = step.selector,
                    category = category,
                    vaultLabel = matchedExisting?.label ?: defaultLabel,
                    capturedValue = rawVal,
                    hasSecretCapture = isPwd && secretOverride.isNotEmpty(),
                    isSensitive = category.isSensitiveDefault,
                    isSelected = !isPwd || secretOverride.isNotEmpty() || matchedExisting != null,
                    existingEntryId = matchedExisting?.entryId
                )
            }
        }

        fun parseImportContent(
            rawContent: String,
            existingEntries: List<AutomationVaultEntryEntity> = emptyList(),
            defaultOriginUrl: String = ""
        ): List<VaultFileImportCandidate> {
            val cleaned = rawContent
                .removePrefix("\uFEFF")
                .trim()
            if (cleaned.isEmpty()) return emptyList()

            val engine = AutomationEngine()
            val normalizedDefaultOrigin = normalizeImportOrigin(engine, defaultOriginUrl)
            val draft = mutableListOf<VaultFileImportCandidate>()

            fun pushCandidate(
                rawCategory: AutomationVaultCategory? = null,
                rawLabel: String,
                rawValue: String,
                rawOrigin: String = "",
                isSensitiveHint: Boolean? = null
            ) {
                val valTrimmed = rawValue.trim()
                if (valTrimmed.isEmpty()) return
                val cleanLabel = rawLabel.trim().ifBlank { "Imported Value" }
                val origin = normalizeImportOrigin(engine, rawOrigin).ifBlank { normalizedDefaultOrigin }
                val category = rawCategory ?: detectCategory(
                    targetLabel = cleanLabel,
                    selector = cleanLabel,
                    value = valTrimmed
                )
                val sensitive = isSensitiveHint ?: category.isSensitiveDefault
                val matchedExisting = existingEntries.firstOrNull { existing ->
                    existing.category.equals(category.id, ignoreCase = true) &&
                        existing.label.equals(cleanLabel, ignoreCase = true) &&
                        (existing.websiteOrigin.isBlank() || origin.isBlank() || existing.websiteOrigin == origin)
                }
                draft.add(
                    VaultFileImportCandidate(
                        id = "file_cand_${draft.size}_${UUID.randomUUID().toString().take(8)}",
                        category = category,
                        label = cleanLabel,
                        value = valTrimmed,
                        websiteOrigin = origin,
                        isSensitive = sensitive || category == AutomationVaultCategory.PASSWORD,
                        isSelected = true,
                        existingEntryId = matchedExisting?.entryId
                    )
                )
            }

            // 1. JSON format (array, container object, or flat key-value object)
            if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
                val parsedJson = parseJsonImport(cleaned, ::pushCandidate)
                if (parsedJson && draft.isNotEmpty()) {
                    return deduplicateCandidates(draft)
                }
            }

            // Split lines for CSV/TSV and key-value/plain text parsing
            val nonBlankLines = cleaned.lines()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }

            // 2. CSV / TSV format
            if (nonBlankLines.isNotEmpty()) {
                val parsedCsv = parseDelimitedImport(nonBlankLines, ::pushCandidate)
                if (parsedCsv && draft.isNotEmpty()) {
                    return deduplicateCandidates(draft)
                }
            }

            // 3. Key-value (.env / txt / ini / colon-separated) & plain text lines
            for (rawLine in nonBlankLines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) continue

                if (line.contains('|')) {
                    val parts = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    when {
                        parts.size >= 3 -> {
                            val site = parts[0]
                            val user = parts[1]
                            val pass = parts[2]
                            val userCat = if (EMAIL_REGEX.matches(user)) AutomationVaultCategory.EMAIL else AutomationVaultCategory.USERNAME
                            pushCandidate(userCat, "$site ${userCat.label}", user, site, false)
                            pushCandidate(AutomationVaultCategory.PASSWORD, "$site Password", pass, site, true)
                        }
                        parts.size == 2 -> {
                            pushCandidate(null, parts[0], parts[1])
                        }
                    }
                    continue
                }

                val eqIdx = line.indexOf('=')
                val colonIdx = findKeyValueColonIndex(line)
                val sepIdx = when {
                    eqIdx in 1..48 && (colonIdx < 0 || eqIdx < colonIdx) -> eqIdx
                    colonIdx in 1..48 -> colonIdx
                    else -> -1
                }
                if (sepIdx > 0) {
                    val left = line.substring(0, sepIdx).trim().trim('"', '\'')
                    val right = line.substring(sepIdx + 1).trim().trim('"', '\'')
                    if (left.isNotEmpty() && right.isNotEmpty()) {
                        // Support credential pair like `alice@example.com:SecretPass123`
                        if (EMAIL_REGEX.matches(left)) {
                            pushCandidate(AutomationVaultCategory.EMAIL, "Account Email", left, "", false)
                            pushCandidate(AutomationVaultCategory.PASSWORD, "Account ($left) Password", right, "", true)
                        } else {
                            pushCandidate(null, prettifyKey(left), right)
                        }
                    }
                } else {
                    val detectedCat = detectCategory("", "", line)
                    val label = when (detectedCat) {
                        AutomationVaultCategory.EMAIL -> "Imported Email"
                        AutomationVaultCategory.PHONE -> "Imported Phone"
                        AutomationVaultCategory.PASSWORD -> "Imported Password"
                        else -> "Imported Value"
                    }
                    pushCandidate(detectedCat, label, line)
                }
            }

            return deduplicateCandidates(draft)
        }

        private fun normalizeImportOrigin(engine: AutomationEngine, raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if (trimmed.contains("://")) trimmed else if (trimmed.contains('.')) "https://$trimmed" else return ""
            return when (val parsed = engine.parseOrigin(withScheme)) {
                is RecordingOriginResult.Valid -> parsed.canonicalOrigin
                else -> ""
            }
        }

        private fun findKeyValueColonIndex(line: String): Int {
            val idx = line.indexOf(':')
            if (idx <= 0) return -1
            val before = line.substring(0, idx).trim()
            if (before.equals("http", ignoreCase = true) || before.equals("https", ignoreCase = true)) {
                return -1
            }
            if (before.contains('/') || before.contains(' ') && before.length > 36) {
                return -1
            }
            return idx
        }

        private fun prettifyKey(raw: String): String {
            return raw.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .split(Regex("""\s+"""))
                .filter { it.isNotEmpty() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
                }
                .ifBlank { "Imported Value" }
        }

        private fun deduplicateCandidates(items: List<VaultFileImportCandidate>): List<VaultFileImportCandidate> {
            val seen = HashSet<String>()
            val out = mutableListOf<VaultFileImportCandidate>()
            for (item in items) {
                val sig = "${item.category.id}|${item.label.lowercase()}|${item.value}|${item.websiteOrigin.lowercase()}"
                if (seen.add(sig)) {
                    out.add(item)
                }
            }
            return out
        }

        private fun parseJsonImport(
            cleaned: String,
            pushCandidate: (
                rawCategory: AutomationVaultCategory?,
                rawLabel: String,
                rawValue: String,
                rawOrigin: String,
                isSensitiveHint: Boolean?
            ) -> Unit
        ): Boolean {
            fun handleObject(obj: JSONObject, fallbackTitle: String = "") {
                val explicitValue = obj.optString("value", "").ifBlank { obj.optString("secret", "") }
                val siteUrl = obj.optString("websiteOrigin", "").ifBlank {
                    obj.optString("origin", "").ifBlank {
                        obj.optString("url", "").ifBlank { obj.optString("website", "") }
                    }
                }
                val title = obj.optString("label", "").ifBlank {
                    obj.optString("name", "").ifBlank {
                        obj.optString("title", "").ifBlank {
                            obj.optString("site", "").ifBlank { fallbackTitle }
                        }
                    }
                }
                if (obj.has("category") && explicitValue.isNotBlank()) {
                    val cat = AutomationVaultCategory.fromId(obj.optString("category"))
                    val sensitive = if (obj.has("isSensitive")) obj.optBoolean("isSensitive") else cat.isSensitiveDefault
                    pushCandidate(cat, title.ifBlank { cat.label }, explicitValue, siteUrl, sensitive)
                    return
                }
                val skipKeys = setOf(
                    "id", "entryid", "note", "notes", "url", "website", "websiteorigin", "origin",
                    "title", "label", "name", "site", "createdat", "updatedat", "lastusedat", "issensitive", "category"
                )
                val keys = obj.keys()
                var emittedStructured = false
                val prefix = title.takeIf { it.isNotBlank() }
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    if (v is JSONObject) {
                        handleObject(v, k)
                        emittedStructured = true
                        continue
                    }
                    if (v is JSONArray) {
                        for (i in 0 until v.length()) {
                            v.optJSONObject(i)?.let {
                                handleObject(it, title)
                                emittedStructured = true
                            }
                        }
                        continue
                    }
                    val strVal = v?.toString()?.trim().orEmpty()
                    if (strVal.isEmpty() || strVal == "null") continue
                    val kLow = k.lowercase()
                    if (kLow in skipKeys && kLow != "name") continue
                    if (kLow == "name" && (obj.has("password") || obj.has("email") || obj.has("username") || obj.has("phone"))) {
                        continue
                    }
                    val pretty = prettifyKey(k)
                    val label = if (!prefix.isNullOrBlank() && !prefix.equals(pretty, ignoreCase = true)) {
                        "$prefix $pretty"
                    } else {
                        pretty
                    }
                    val detected = detectCategory(k, k, strVal)
                    pushCandidate(detected, label, strVal, siteUrl, detected.isSensitiveDefault)
                    emittedStructured = true
                }
                if (!emittedStructured && title.isNotBlank() && explicitValue.isNotBlank()) {
                    pushCandidate(null, title, explicitValue, siteUrl, null)
                }
            }

            return runCatching {
                if (cleaned.startsWith("[")) {
                    val arr = JSONArray(cleaned)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { handleObject(it) }
                    }
                    true
                } else {
                    val obj = JSONObject(cleaned)
                    val containerKey = listOf("items", "entries", "credentials", "logins", "values", "vault")
                        .firstOrNull { obj.optJSONArray(it) != null }
                    if (containerKey != null) {
                        val arr = obj.getJSONArray(containerKey)
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { handleObject(it) }
                        }
                    } else {
                        handleObject(obj)
                    }
                    true
                }
            }.getOrDefault(false)
        }

        private fun parseDelimitedImport(
            lines: List<String>,
            pushCandidate: (
                rawCategory: AutomationVaultCategory?,
                rawLabel: String,
                rawValue: String,
                rawOrigin: String,
                isSensitiveHint: Boolean?
            ) -> Unit
        ): Boolean {
            val first = lines.firstOrNull() ?: return false
            val delimiter = when {
                first.contains('\t') -> '\t'
                first.contains(',') -> ','
                else -> return false
            }
            val rows = lines.map { parseCsvRow(it, delimiter) }.filter { row -> row.any { it.isNotBlank() } }
            if (rows.isEmpty()) return false

            val header = rows.first().map { it.trim().lowercase() }
            val hasRecognizedHeader = header.any {
                it in setOf(
                    "password", "pass", "pwd", "username", "user", "login",
                    "email", "e-mail", "phone", "mobile", "tel",
                    "value", "secret", "category", "type", "label", "name", "title", "url", "website", "address", "fullname"
                )
            }

            if (hasRecognizedHeader && rows.size >= 2) {
                val idxCategory = header.indexOfFirst { it == "category" || it == "type" }
                val idxValue = header.indexOfFirst { it == "value" || it == "secret" }
                val idxLabel = header.indexOfFirst { it == "label" || it == "name" || it == "title" || it == "site" }
                val idxUrl = header.indexOfFirst { it == "url" || it == "website" || it == "origin" || it == "domain" }

                for (r in 1 until rows.size) {
                    val row = rows[r]
                    val urlVal = idxUrl.takeIf { it in row.indices }?.let { row[it].trim() }.orEmpty()
                    val titleVal = idxLabel.takeIf { it in row.indices }?.let { row[it].trim() }.orEmpty()

                    if (idxCategory >= 0 && idxValue >= 0 && idxValue in row.indices) {
                        val rawVal = row[idxValue].trim()
                        if (rawVal.isNotEmpty()) {
                            val cat = AutomationVaultCategory.fromId(row.getOrNull(idxCategory))
                            val lbl = titleVal.ifBlank { cat.label }
                            pushCandidate(cat, lbl, rawVal, urlVal, cat.isSensitiveDefault)
                        }
                        continue
                    }

                    // Otherwise iterate each value column (supports Chrome/Brave/Bitwarden CSV + custom multi-column CSVs)
                    val metaIndices = setOfNotNull(
                        idxUrl.takeIf { it >= 0 },
                        header.indexOfFirst { it == "note" || it == "notes" }.takeIf { it >= 0 }
                    )
                    val nonMetaPopulated = header.indices.filter { it !in metaIndices && !row.getOrNull(it).isNullOrBlank() }
                    for (colIdx in nonMetaPopulated) {
                        val colName = header[colIdx]
                        if ((colIdx == idxLabel) && nonMetaPopulated.size > 1 && colName in setOf("name", "title", "label", "site")) {
                            continue
                        }
                        val cellVal = row[colIdx].trim()
                        if (cellVal.isEmpty()) continue
                        val detected = detectCategory(colName, colName, cellVal)
                        val colNice = prettifyKey(colName)
                        val finalLabel = if (titleVal.isNotBlank() && !titleVal.equals(colNice, ignoreCase = true)) {
                            "$titleVal $colNice"
                        } else {
                            colNice
                        }
                        pushCandidate(detected, finalLabel, cellVal, urlVal, detected.isSensitiveDefault)
                    }
                }
                return true
            }

            // Headerless CSV: support `label,value` or `category,label,value[,url]`
            if (rows.all { it.size >= 2 }) {
                for (row in rows) {
                    when {
                        row.size >= 3 && AutomationVaultCategory.entries.any {
                            it.id.equals(row[0].trim(), true) || it.label.equals(row[0].trim(), true)
                        } -> {
                            val cat = AutomationVaultCategory.fromId(row[0])
                            val lbl = row[1].trim().ifBlank { cat.label }
                            val valStr = row[2].trim()
                            val originStr = row.getOrNull(3)?.trim().orEmpty()
                            pushCandidate(cat, lbl, valStr, originStr, cat.isSensitiveDefault)
                        }
                        row.size >= 2 -> {
                            val lbl = row[0].trim()
                            val valStr = row[1].trim()
                            val originStr = row.getOrNull(2)?.trim().orEmpty()
                            pushCandidate(null, lbl, valStr, originStr, null)
                        }
                    }
                }
                return true
            }
            return false
        }

        private fun parseCsvRow(line: String, delimiter: Char): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (ch == delimiter && !inQuotes) {
                    out.add(cur.toString())
                    cur.clear()
                } else {
                    cur.append(ch)
                }
                i++
            }
            out.add(cur.toString())
            return out
        }
    }
}
