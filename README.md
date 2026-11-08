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

## Gói ngôn ngữ
- Nút **Gói ngôn ngữ (tải thêm)**: danh sách ngôn ngữ nguồn (Nhật, Trung, Pháp, Tây Ban Nha, Thái...). Chạm để tải (~30MB/gói), chạm lần nữa để xóa.
- Anh và Hàn là mặc định, tự tải khi cần. Ngôn ngữ khác phải tải trước, nếu không app sẽ nhắc và chỉ phát video.
- Gói đã tải sẽ xuất hiện trong ô chọn ngôn ngữ phụ đề.

## Video reaction
- Bật **Video reaction** (tự bật nếu tiêu đề có chữ react/reaction/리액션/リアクション) để chỉ dịch người đang nói.
- Cách lọc: bỏ các câu có ngôn ngữ khác ngôn ngữ người nói (thường là lời bài hát/clip gốc); câu điệp khúc lặp ≥3 lần coi là lời hát.
- Hiệu quả nhất khi phụ đề do người làm hoặc khi bài hát khác ngôn ngữ với người reaction. Nếu cùng ngôn ngữ và phụ đề chỉ là tự động thì chỉ lọc được phần có [Music]/♪ hoặc điệp khúc lặp.

## Hát / nhạc
- Đoạn có ký hiệu ♪, hoặc nhãn [singing]/[rapping]/[hát]... hiện **[hát]**; đoạn [Music]/[nhạc]... hiện **[nhạc]**. Các đoạn này không dịch và không đọc.

## Giới hạn
- Video phải có phụ đề (kể cả tự động). Không có phụ đề thì chỉ phát video.
- Giọng TTS hơi máy; câu dịch quá dài có thể bị cắt khi tới câu kế tiếp.
- Chưa có phát nền / tắt màn hình (app dừng khi thoát màn hình).
- Nếu YouTube đổi cách phát và video không tải được: nâng phiên bản `NewPipeExtractor` trong `app/build.gradle.kts` lên bản mới nhất tại https://github.com/TeamNewPipe/NewPipeExtractor/releases
