// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录（仅支持中国大陆 +86 手机号）。
 * * [send]：先匿名注册一台**新设备**（[DeviceRegister]），再用这台设备的身份发码。 设备注册仅是前置条件；短信登录换取会话尚未完成实机验收。
 * * [verify]：使用同一设备完成官方 Passport 短信登录；遇到上行短信挑战时，从响应读取目标号码和正文，
 *   由界面打开短信编辑页，用户发送后轮询官方验证接口并使用返回票据重试登录。
 *
 * 一个实例对应一次登录；重新发码会换一台新设备，之前的验证码随之作废。
 */
class PhoneLogin(private val http: OkHttpClient) {
    var deviceId = ""
    var installId = ""
    var mobile = ""
    var ticket = ""
    var type = LOGIN_CODE_TYPE
    private var expired = false
    val cookies = linkedMapOf<String, String>()
    /** Compatibility field; authentication responses are never retained here. */
    val trace = mutableListOf<String>()

    /** 平台拒绝时的可读原因，界面直接显示 [message]。 */
    class Failure(message: String, val errorCode: Int? = null, val canRetryCode: Boolean = false) :
        Exception(message)

    /** [maskedMobile] 是平台回显的打码号码。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var cancelled = false
    @Volatile private var token = ""
    private var pendingMfa: UpSmsChallenge? = null
    private var pendingMfaExpiresAt = 0L

    data class UpSmsChallenge(val channelMobile: String, val smsContent: String, val encryptUid: String, val smsCodeKey: String, val logId: String)
    class UpSmsRequired(val challenge: UpSmsChallenge) :
        Exception("平台要求发送一条验证短信，请按页面提示完成验证")

    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    fun send(mobile: String): Sent {
        cancel()
        cancelled = false
        val registered =
            CloudAccountClient(http).registerDevice() ?: throw Failure("设备注册失败，请检查网络后重试")
        deviceId = registered.deviceId
        installId = registered.installId
        cookies.clear()
        cookies.putAll(registered.cookies)
        token = ""
        expired = false
        pendingMfa = null
        pendingMfaExpiresAt = 0L
        this.mobile = mobile
        val reply = call(
            "/passport/mobile/send_code/v1/",
            mapOf("auto_read" to "0", "unbind_exist" to "34", "type" to xor("24")),
        )
        val data = reply.optJSONObject("data") ?: JSONObject()
        if (data.has("verify_center_decision_conf"))
            throw Failure("平台要求先完成滑块验证，请在官方红果 App 完成验证后再试。")
        if (!isSuccess(reply)) throw Failure(describe(data) ?: "验证码没发出去")
        ticket = data.optString("mobile_ticket")
        return Sent(data.optString("mobile").ifBlank { mobile }, data.optInt("retry_time", 60))
    }

    fun verify(code: String): AccountCredentials {
        if (expired) throw Failure("本次登录会话已失效，请重新获取验证码", 1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4–8 位数字验证码" }
        val reply = smsLogin(code, "")
        if (isSuccess(reply)) return credentialsOrThrow()
        val data = reply.optJSONObject("data") ?: JSONObject()
        parseUpSmsChallenge(data)?.let {
            pendingMfa = it
            pendingMfaExpiresAt = System.currentTimeMillis() + 10 * 60 * 1000L
            throw UpSmsRequired(it)
        }
        throw Failure(describe(reply, data) ?: "验证码校验失败", errorCode(reply), errorCode(reply) == 1102)
    }

    /** 用户从短信编辑页返回并确认已发送后调用；最多 90 秒，每 3 秒查询一次。 */
    fun verifyUpSmsAndLogin(code: String): AccountCredentials {
        if (expired) throw Failure("本次登录会话已失效，请重新获取验证码", 1)
        val challenge = pendingMfa ?: throw Failure("当前没有待确认的上行短信验证，请重新提交验证码")
        if (System.currentTimeMillis() >= pendingMfaExpiresAt) {
            pendingMfa = null
            pendingMfaExpiresAt = 0L
            expired = true
            throw Failure("上行短信验证已过期，请重新获取验证码。", 1)
        }
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4–8 位数字验证码" }
        var lastDescription = "尚未收到平台确认"
        val confirmationDeadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < confirmationDeadline) {
            if (cancelled || Thread.currentThread().isInterrupted) throw Failure("登录已取消")
            if (System.currentTimeMillis() >= pendingMfaExpiresAt) {
                pendingMfa = null
                pendingMfaExpiresAt = 0L
                expired = true
                throw Failure("上行短信验证已过期，请重新获取验证码。", 1)
            }
            val reply = call(
                "/passport/upsms/verify/",
                mapOf(
                    "encrypt_uid" to challenge.encryptUid,
                    "verify_ticket" to "",
                    "verify_scene" to "sms_login",
                    "verify_reason" to "ato",
                    "copywriting_key" to "sms_login",
                    "ies_safety_diversion_tag" to "mfa",
                    "new_verify_flow" to "",
                    "new_authn_sdk_version" to "1.1.31",
                    "new_authn_sdk_verify_reason" to "ato",
                    "new_authn_sdk_verify_scene" to "sms_login",
                    "request_tag_from" to "h5",
                    "biz_params" to JSONObject()
                        .put("passport_mfa_retry_tag", "1")
                        .put("sms_code_key", challenge.smsCodeKey)
                        .toString(),
                    "new_authn_sdk_log_id" to challenge.logId,
                ),
            )
            val pollCode = errorCode(reply)
            // 1045 means "not confirmed yet" and is the only response safe to poll.
            // 1066 is terminal for this challenge: clear it immediately so a second tap
            // cannot reuse the expired challenge and spin for another 90 seconds.
            if (pollCode == 1066) {
                pendingMfa = null
                pendingMfaExpiresAt = 0L
                expired = true
                throw Failure("平台返回 1066：本次验证会话已失效，请重新获取短信验证码后再试。", 1066)
            }
            if (pollCode == 1045) {
                lastDescription = describe(reply, reply.optJSONObject("data") ?: JSONObject()) ?: lastDescription
                val remaining = confirmationDeadline - System.currentTimeMillis()
                if (remaining > 0L) Thread.sleep(minOf(3000L, remaining))
                continue
            }
            if (!isSuccess(reply)) {
                val data = reply.optJSONObject("data") ?: JSONObject()
                val description = describe(reply, data) ?: "上行短信验证失败"
                if (description.contains("过期") || description.contains("失效")) {
                    expired = true
                    pendingMfa = null
                }
                throw Failure(description, errorCode(reply))
            }
            val data = reply.optJSONObject("data") ?: JSONObject()
            val mfaTicket = data.optString("ticket").ifBlank { data.optString("verify_ticket") }
            if (mfaTicket.isBlank()) throw Failure("上行短信验证响应缺少登录票据")
            val loginReply = smsLogin(code, mfaTicket)
            if (!isSuccess(loginReply)) {
                val loginData = loginReply.optJSONObject("data") ?: JSONObject()
                throw Failure(describe(loginReply, loginData) ?: "验证通过，但账号登录未完成", errorCode(loginReply))
            }
            pendingMfa = null
            pendingMfaExpiresAt = 0L
            return credentialsOrThrow()
        }
        throw Failure("等待平台确认超时（90 秒），请确认短信已成功发送后重试。$lastDescription", 1045, true)
    }

    private fun smsLogin(code: String, mfaTicket: String): JSONObject {
        val params = linkedMapOf("code" to xor(code))
        if (mfaTicket.isNotBlank()) {
            params["passport_mfa_retry_tag"] = "1"
            params["sms_code_key"] = xor(mfaTicket)
        }
        return call("/passport/mobile/sms_login/", params)
    }

    private fun credentialsOrThrow(): AccountCredentials {
        if (!cookies.keys.any { it == "sessionid" || it == "sessionid_ss" }) {
            throw Failure("平台返回登录成功，但没有下发有效会话，请重试。")
        }
        val header = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header).copy(
            deviceId = deviceId,
            installId = installId,
            device = mapOf("device_id" to deviceId, "iid" to installId),
        )
    }

    private fun parseUpSmsChallenge(data: JSONObject): UpSmsChallenge? {
        val ways = data.optJSONArray("verify_ways") ?: return null
        var number = ""
        var content = ""
        for (i in 0 until ways.length()) {
            val way = ways.optJSONObject(i) ?: continue
            if (way.optString("verify_way") == "upsms" || way.optString("sms_content").isNotBlank()) {
                number = way.optString("channel_mobile")
                content = way.optString("sms_content")
                if (number.isNotBlank() && content.isNotBlank()) break
            }
        }
        val encryptUid = data.optString("encrypt_uid")
        val codeKey = data.optString("sms_code_key")
        val logId = data.optJSONObject("event_params")?.optString("log_id").orEmpty()
        if (encryptUid.isBlank() || codeKey.isBlank() || number.isBlank() || content.isBlank()) return null
        return UpSmsChallenge(number, content, encryptUid, codeKey, logId)
    }

    /** Signed Passport form request. Authentication material is never written to trace/logs. */
    private fun call(
        path: String,
        params: Map<String, String>,
    ): JSONObject {
        if (cancelled) throw Failure("登录已取消")
        val device = LinkedHashMap(VendorConstants.device).apply {
            put("device_id", deviceId)
            put("iid", installId)
            for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player"))
                put(key, "73932")
            put("version_name", "7.3.9.32")
        }
        val query = device + mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android")
        val bodyFields = linkedMapOf(
            "mobile" to xor(wirePhone(mobile)),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1",
        ).apply { putAll(params) }
        val payload = Signer.encode(bodyFields).toByteArray(Charsets.UTF_8)
        val signed = Signer(device = device).sign(PASSPORT + path, query, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        request.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        request.header("Accept", "application/json")
        request.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        request.header("X-XS-From-Web", "0")
        request.header("Sdk-Version", "2")
        request.header("Passport-Sdk-Version", "5051452")
        request.header("X-SS-DP", "8662")
        request.header("X-Tt-Store-Region", "cn")
        request.header("X-Tt-Store-Region-Src", "did")
        request.header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
        request.header("Lc", "101")
        if (cookies.isNotEmpty()) request.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        if (token.isNotBlank()) request.header("X-Tt-Token", token)
        request.post(payload.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        val call = http.newCall(request.build())
        activeCall = call
        val reply = try {
            call.execute().use { response ->
                response.headers("Set-Cookie").forEach { line ->
                    val pair = line.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
                val responseToken = response.header("X-Tt-Token")
                if (!responseToken.isNullOrBlank()) token = responseToken
                val text = cloudResponse(response)
                runCatching { JSONObject(text) }.getOrElse {
                    JSONObject().put("http", response.code).put("raw", text.take(300))
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
        return reply
    }

    private fun isSuccess(reply: JSONObject): Boolean {
        val data = reply.optJSONObject("data") ?: return false
        val code = when {
            data.has("error_code") -> data.optString("error_code")
            reply.has("error_code") -> reply.optString("error_code")
            else -> ""
        }
        return reply.optString("message") == "success" && (code.isBlank() || code == "0")
    }

    private fun errorCode(reply: JSONObject): Int {
        val data = reply.optJSONObject("data")
        return if (data?.has("error_code") == true) data.optInt("error_code", -1)
        else reply.optInt("error_code", -1)
    }

    private fun xor(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { "%02x".format((it.toInt() and 0xff) xor 5) }

    private fun wirePhone(value: String): String {
        if (!value.startsWith("+")) return value
        val code = value.drop(1).takeWhile(Char::isDigit)
        val local = value.drop(1 + code.length).filter(Char::isDigit)
        val widths = when (local.length) {
            10 -> listOf(3, 4, 3)
            11 -> listOf(3, 4, 4)
            else -> listOf(3, 3, 3, 3)
        }
        var remaining = local
        val groups = widths.mapNotNull { width ->
            if (remaining.isEmpty()) null else {
                val group = remaining.take(width)
                remaining = remaining.drop(width)
                group
            }
        } + listOfNotNull(remaining.takeIf { it.isNotEmpty() })
        return "+$code " + groups.joinToString(" ")
    }

    /**
     * Passport 有时把 description/error_code 放在 data 内，有时放在响应顶层。
     * 统一读取，避免把有用的服务端拒绝原因吞成“上行短信验证失败”。
     * 不显示原始响应，避免意外暴露票据、手机号或 Cookie。
     */
    private fun describe(reply: JSONObject, data: JSONObject): String? {
        val description = sequenceOf(
            data.optString("description"),
            reply.optString("description"),
            data.optString("message"),
            reply.optString("message"),
        ).firstOrNull { it.isNotBlank() && it != "success" } ?: return null

        val code = errorCode(reply)
        val safeDescription = description
            .replace(Regex("[0-9]{7,}"), "***")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(120)
        return if (code >= 0) "$safeDescription（错误码 $code）" else safeDescription
    }

    companion object {
        private const val PASSPORT = "https://api5-normal-lq.fqnovel.com"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        const val LOGIN_CODE_TYPE = "24"
        /** 仅支持中国大陆手机号：1 开头的 11 位数字。 */
        fun format(countryCode: String, number: String): String {
            val cc = countryCode.filter(Char::isDigit)
            require(cc == "86") { "目前仅支持中国大陆 +86 手机号" }
            val digits = number.filter(Char::isDigit)
            require(digits.matches(Regex("1\\d{10}"))) { "请输入 1 开头的 11 位大陆手机号" }
            return digits
        }

        /** 只接受大陆 11 位手机号；不再接受境外区号格式。 */
        fun parse(raw: String): String {
            val text = raw.trim()
            require(!text.startsWith("+")) { "目前仅支持中国大陆 +86 手机号，请输入 11 位手机号" }
            return format("86", text)
        }
    }
}
