# Auto Click (Android, không cần root)

Tool tự động click cho game Unity: đặt vị trí, thời gian, thứ tự; lưu thành các luồng có tên;
tick chọn luồng để chạy tuần tự; có thể chờ một đoạn chữ hiện trên nút rồi mới click (OCR).

## Cài đặt & build

1. Mở thư mục này bằng **Android Studio** (bản mới, có JDK 17), chờ Gradle sync.
   - Project chưa kèm `gradle-wrapper.jar`/`gradlew`. Android Studio sẽ tự tải Gradle 8.7 theo
     `gradle/wrapper/gradle-wrapper.properties`. Nếu nó đề xuất nâng cấp phiên bản AGP/Kotlin/Compose
     thì cứ đồng ý (khi nâng Kotlin lên 2.x cần thêm plugin `org.jetbrains.kotlin.plugin.compose`).
2. Cắm điện thoại (bật Gỡ lỗi USB) → **Run ▶**. Hoặc *Build → Build APK(s)* rồi cài APK.
3. Mở app → bấm **Mở cài đặt Trợ năng** → *Ứng dụng đã tải xuống → Auto Click* → **Bật**.
   - Android 13+ nếu bị chặn: *Cài đặt → Ứng dụng → Auto Click → ⋮ → Cho phép cài đặt bị hạn chế*, rồi bật lại.

Yêu cầu: **Android 11 (API 30) trở lên** (cần `takeScreenshot` cho OCR).

## Cách dùng

1. **＋ Thêm luồng** → đặt tên.
2. Trong màn sửa luồng bấm **📍 Đặt / chỉnh vị trí click**. App lùi xuống nền, bạn mở game; trên game
   hiện các chấm đánh số. **Kéo** từng chấm đến đúng nút cần click, bấm **＋ Thêm điểm** để thêm bước,
   xong bấm **✔ Xong** (thanh công cụ kéo được bằng nút ☰).
3. Với mỗi bước chỉnh trong app:
   - *Chờ trước (ms)*: thời gian chờ trước khi click.
   - *Số lần click* và *Cách nhau (ms)*.
   - *Chờ chữ hiện ra rồi mới click*: gõ đúng chữ trên nút. App đọc chữ quanh vị trí bước (OCR) mỗi ~0,45 giây
     và chỉ click khi thấy chữ khớp. Không giới hạn thời gian chờ (Timeout = 0), hoặc đặt Timeout để bỏ
     qua bước khi hết giờ.
   - Nút ▲ ▼ đổi thứ tự bước.
4. Ở màn hình chính:
   - **Checkbox** để chọn luồng. Thứ tự chạy = thứ tự trong danh sách (▲ ▼ để sắp xếp),
     ví dụ tick luồng 1, 2, 4 và bỏ luồng 3 thì chạy 1 → 2 → 4.
   - Ô **Lặp (lần)** trên mỗi luồng: luồng đó chạy liên tiếp đủ số lần rồi mới sang luồng tiếp theo.
   - **Số vòng** (ở dưới): lặp lại cả chuỗi các luồng đã tick; `0` = lặp vô hạn.
   - **Đếm ngược**: số giây chờ sau khi bấm Start để bạn kịp mở game.
5. Bấm **▶ START**. Thanh nổi hiển thị trạng thái (đang ở bước nào, đang thấy chữ gì); bấm **■ DỪNG** để dừng.

## Mẹo cho OCR (game Unity)

- Game không có cây giao diện nên app chụp màn hình + đọc chữ bằng ML Kit (offline). Chữ nên rõ, tương phản.
- Nếu không khớp: nhìn dòng *"đang thấy: …"* trên thanh nổi, rồi gõ lại chữ cho giống, hoặc
  tăng *Vùng đọc rộng/cao*. Khi so khớp app bỏ hoa/thường, bỏ dấu và bỏ khoảng trắng.
  OCR có thể nhầm ký tự giống nhau (0/O, 1/l) nên nên chọn đoạn chữ dễ đọc.
- Đặt thanh nổi ra xa vùng chữ cần đọc để không che chữ.

## Giới hạn

- App/game đặt `FLAG_SECURE` (ngân hàng, một số game) sẽ chặn chụp màn hình, OCR không dùng được ở đó.
- Một số game phát hiện Trợ năng/auto click và có thể cấm tài khoản. Hãy tự cân nhắc rủi ro.
- Nếu click bị lệch cố định (ví dụ đúng bằng chiều cao thanh trạng thái) thì cần chỉnh lại gốc tọa độ trong
  `OverlayController`.

## Cấu trúc code

| File | Vai trò |
|---|---|
| `service/ClickAccessibilityService.kt` | click (`dispatchGesture`), chụp màn hình, OCR, chạy tuần tự các luồng |
| `service/OverlayController.kt` | chấm kéo thả đặt vị trí, thanh công cụ, thanh DỪNG nổi |
| `service/TextMatcher.kt` | chuẩn hoá & so khớp chữ |
| `data/FlowStore.kt` | lưu luồng vào `flows.json` |
| `model/Models.kt` | `ClickFlow`, `Step` |
| `ui/Screens.kt` | màn hình chính (checkbox, lặp, Start) và màn sửa luồng |
