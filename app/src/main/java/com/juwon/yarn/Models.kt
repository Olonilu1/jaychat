package com.juwon.yarn

data class Profile(val id: String, val username: String, val display_name: String, val about: String = "", val avatar_url: String = "", val last_seen: String = "")
data class Chat(
    val id: String,
    val kind: String, // "dm" or "group"
    val name: String, // for group
    val other_username: String,
    val other_display: String,
    val last_message: String?,
    val last_at: String?
)
data class Message(
    val id: String,
    val chat_id: String,
    val sender_id: String,
    val sender_username: String,
    val sender_display: String,
    val body: String,
    val created_at: String,
    val kind: String = "text",
    val attachment_url: String = "",
    val read_at: String = "",
    val reply_to: String = "",
    val reply_preview: String = "",
    val expires_at: String = ""
)
