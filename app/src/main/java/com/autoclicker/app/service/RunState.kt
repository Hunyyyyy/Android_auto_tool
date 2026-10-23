package com.autoclicker.app.service

import kotlinx.coroutines.flow.MutableStateFlow

/** Trạng thái dùng chung để giao diện tự cập nhật. */
object RunState {
    /** Dịch vụ Trợ năng đang được bật và kết nối. */
    val connected = MutableStateFlow(false)

    /** Đang chạy các luồng. */
    val running = MutableStateFlow(false)

    /** Đếm ngược (giây) trước khi bắt đầu chạy, mặc định 3. Dùng chung cho app và bong bóng. */
    val startDelaySec = MutableStateFlow(3)
}
