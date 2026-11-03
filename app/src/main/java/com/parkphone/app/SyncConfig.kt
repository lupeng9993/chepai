package com.parkphone.app

/**
 * 同步内置参数（家专用版）。
 *
 * ⚠️ 本文件含网盘账号与应用密码：仓库必须为 Private，APK 只限家人间安装，不外传。
 * 应用密码可在坚果云网页「安全选项 → 第三方应用管理」随时吊销后重新生成，
 * 换密码后只需替换 APP_PWD 一行并重新构建。
 */
object SyncConfig {

    /** 坚果云 WebDAV 固定入口 + 专用同步目录 */
    const val BASE_URL = "https://dav.jianguoyun.com/dav/sync/"

    const val USER = "5567218@163.com"
    const val APP_PWD = "awbtiqezdhngtn6q"

    /** 加密口令：两台手机必须一致，不一致时数据不可读 */
    const val PASSPHRASE = "xiaozhao-sync-2026"

    /** 接收方识别令牌：隐藏区里号码为该值的记录存在即为接收端 */
    const val RECEIVER_TOKEN = "114"

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
}
