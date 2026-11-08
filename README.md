# ViDub — xem YouTube có lồng tiếng Việt (Hàn/Anh → Việt)

Dán link YouTube → video hiện ra, giọng tiếng Việt trong máy đọc phụ đề đã dịch.

## Cách hoạt động
- **NewPipeExtractor** (thư viện của NewPipe): lấy video + phụ đề, không cần API key, không quảng cáo.
- **ExoPlayer**: phát video.
- **ML Kit Translate**: dịch offline Hàn/Anh → Việt (lần đầu tải gói ngôn ngữ ~30MB).
- **TextToSpeech của máy**: đọc tiếng Việt, tự khớp theo thời gian từng câu, tự tăng tốc khi câu dài, hạ tiếng gốc khi đang đọc.

## Build ra APK (không cần cài Android Studio)
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên (nhánh `main`).
2. Vào tab **Actions** → chạy workflow **Build APK** (tự chạy khi push, hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó → mục **Artifacts** → tải `ViDub-debug-apk` → giải nén lấy `app-debug.apk`.
4. Chép vào điện thoại, cài đặt (cho phép cài từ nguồn không xác định).

Nếu build lỗi, copy dòng lỗi trong tab Actions gửi lại để sửa.

## Dùng
- Dán link rồi bấm **Xem**, hoặc từ app YouTube / trình duyệt bấm **Chia sẻ → ViDub**.
- Chọn ngôn ngữ phụ đề gốc (Tự động / English / 한국어). Tự động ưu tiên: phụ đề tiếng Việt có sẵn → Hàn → Anh, bản người làm trước bản tự động.
- Máy cần có giọng tiếng Việt: Cài đặt → Chuyển văn bản thành giọng nói (Google) → tải tiếng Việt.

## Giới hạn
- Video phải có phụ đề (kể cả tự động). Không có phụ đề thì chỉ phát video.
- Giọng TTS hơi máy; câu dịch quá dài có thể bị cắt khi tới câu kế tiếp.
- Chưa có phát nền / tắt màn hình (app dừng khi thoát màn hình).
- Nếu YouTube đổi cách phát và video không tải được: nâng phiên bản `NewPipeExtractor` trong `app/build.gradle.kts` lên bản mới nhất tại https://github.com/TeamNewPipe/NewPipeExtractor/releases
