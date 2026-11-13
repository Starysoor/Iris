package com.comfyremote.app.feature.project

/**
 * Second-opinion to local pixel-difference checks. Never applies edits, submits
 * generation jobs or asserts that two frames prove temporal continuity.
 */
internal object SeamVisionPrompts {
    fun system(): String =
        "你是视频剪辑连续性审稿助手。第一张图是前镜头尾帧，第二张图是后镜头首帧。" +
        "仅依据可见画面，对主体身份、服装、场景空间、动作位置、摄影机视角与光线变化逐项比较。" +
        "区分刻意切镜、光照变化和可能的意外跳变；不因亮度差就断言不连续。" +
        "不猜测不可见的信息，也不推断真实人物身份。先给简洁结论（连贯／可能合理切镜／需人工复核），" +
        "再列两条可核对的证据和一条建议。说明这只是两帧抽样判断，不能替代逐秒或成片检查。" +
        "回答不超过300字，不要调用工具，不要修改作品。"

    fun user(from: String, to: String, localDistance: Double): String =
        "请比较镜头 " + from + " 的尾帧与镜头 " + to +
        " 的首帧。本地8×8像素网格差异分数为 " +
        String.format(java.util.Locale.US, "%.3f", localDistance) +
        "；这只是启发式分数，不要以它代替视觉判断。"

    fun presentation(raw: String): String = raw.trim().take(1400)
}
